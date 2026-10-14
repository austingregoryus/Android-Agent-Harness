package dev.symbiosis.runtime.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import dev.symbiosis.runtime.policy.ActionClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * MVP 5 research probe (DEBUG builds only): during an active call, try every AudioRecord source and report
 * which ones initialise and which carry signal. This answers the spec's biggest unknown — whether this
 * device exposes carrier call audio to an ordinary app — without any production code depending on it.
 */
object AudioProbe {
  private data class Src(val name: String, val id: Int)

  private val SOURCES = listOf(
    Src("MIC", MediaRecorder.AudioSource.MIC),
    Src("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    Src("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    Src("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
    Src("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER),
    Src("VOICE_CALL", MediaRecorder.AudioSource.VOICE_CALL),
    Src("VOICE_UPLINK", MediaRecorder.AudioSource.VOICE_UPLINK),
    Src("VOICE_DOWNLINK", MediaRecorder.AudioSource.VOICE_DOWNLINK),
    Src("VOICE_PERFORMANCE", MediaRecorder.AudioSource.VOICE_PERFORMANCE),
  )

  fun capability(context: Context): Capability = Capability(
    name = "debug.audio_probe",
    description = "RESEARCH (debug builds only). Try each AudioRecord source for `seconds` and report init state, RMS and peak. Run during an active call to learn what call audio this device exposes. route=speaker also tests the acoustic-bridge baseline.",
    params = listOf(
      Param("seconds", "integer", "Capture length per source (default 2, max 5)"),
      Param("route", "string", "earpiece (default) or speaker: set the communication device before probing", enum = listOf("earpiece", "speaker")),
      Param("sources", "array", "Subset of source names to try (default: all)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
    requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO),
  ) { args, _ ->
    val secs = ((args["seconds"] as? Number)?.toInt() ?: 2).coerceIn(1, 5)
    val want = (args["sources"] as? List<*>)?.map { it.toString().uppercase() }?.toSet()
    val am = context.getSystemService(AudioManager::class.java)
    val routeApplied = if (args["route"] == "speaker" && Build.VERSION.SDK_INT >= 31) {
      am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) } ?: false
    } else null
    val results = withContext(Dispatchers.IO) {
      SOURCES.filter { want == null || it.name in want }.map { probe(it, secs) }
    }
    if (Build.VERSION.SDK_INT >= 31 && args["route"] == "speaker") runCatching { am.clearCommunicationDevice() }
    CapabilityResult.ok(
      mapOf(
        "audio_mode" to modeName(am.mode),
        "route_speaker_applied" to routeApplied,
        "communication_device" to if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { deviceName(it) } else null,
        "sdk" to Build.VERSION.SDK_INT,
        "model" to Build.MODEL,
        "results" to results,
      ),
    )
  }

  private fun probe(src: Src, secs: Int): Map<String, Any?> {
    val rate = 16_000
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return mapOf("source" to src.name, "init" to false, "error" to "getMinBufferSize=$minBuf")
    var rec: AudioRecord? = null
    return try {
      @Suppress("MissingPermission")
      rec = AudioRecord(src.id, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate * 2))
      if (rec.state != AudioRecord.STATE_INITIALIZED) return mapOf("source" to src.name, "init" to false, "error" to "state=${rec.state}")
      rec.startRecording()
      val recording = rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
      val buf = ShortArray(rate / 10) // 100 ms
      var frames = 0L; var sumSq = 0.0; var peak = 0; var zeroReads = 0; var t = System.currentTimeMillis()
      val end = t + secs * 1000L
      while (System.currentTimeMillis() < end) {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) { zeroReads++; if (zeroReads > 20) break; continue }
        for (i in 0 until n) { val v = buf[i].toInt(); sumSq += (v * v).toDouble(); if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
        frames += n
      }
      val rms = if (frames > 0) sqrt(sumSq / frames) else 0.0
      mapOf(
        "source" to src.name, "init" to true, "recording" to recording, "frames" to frames,
        "rms" to Math.round(rms), "peak" to peak, "rms_dbfs" to if (rms > 0) Math.round(20 * kotlin.math.log10(rms / 32768.0) * 10) / 10.0 else null,
        "signal" to (rms > 30 && frames > 0), "read_errors" to zeroReads,
        "device" to rec.routedDevice?.let { deviceName(it) },
      )
    } catch (t: Throwable) {
      mapOf("source" to src.name, "init" to false, "error" to "${t.javaClass.simpleName}: ${t.message}")
    } finally {
      runCatching { rec?.stop() }; runCatching { rec?.release() }
    }
  }

  /**
   * Uplink-injection test (debug builds only): play a distinctive gated 1 kHz tone into a chosen audio
   * stream during a call, so a human on the far end can report whether it carries onto the call TX path.
   * Only needs MODIFY_AUDIO_SETTINGS; run during an active call and ask the callee what they heard.
   */
  fun injectCapability(context: Context): Capability = Capability(
    name = "debug.audio_inject",
    description = "RESEARCH (debug). Play a gated 1 kHz tone to `stream` for `seconds` during a call. Ask the far party if they heard it (uplink injection test). streams: voice_call, voice_communication, media, ring, alarm, accessibility.",
    params = listOf(
      Param("seconds", "integer", "Tone length (default 4, max 10)"),
      Param("stream", "string", "voice_call (default) | voice_communication | media | ring | alarm | accessibility"),
      Param("gain", "number", "0..1 amplitude (default 0.6)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, _ ->
    val secs = ((args["seconds"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)
    val gain = ((args["gain"] as? Number)?.toDouble() ?: 0.6).coerceIn(0.0, 1.0)
    val stream = (args["stream"]?.toString() ?: "voice_call").lowercase()
    val am = context.getSystemService(AudioManager::class.java)
    withContext(Dispatchers.IO) { injectTone(stream, secs, gain) }.let { CapabilityResult.ok(it + mapOf("audio_mode" to modeName(am.mode), "stream" to stream)) }
  }

  private fun injectTone(stream: String, secs: Int, gain: Double): Map<String, Any?> {
    val rate = 16_000
    val usage = when (stream) {
      "voice_communication" -> android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
      "media" -> android.media.AudioAttributes.USAGE_MEDIA
      "ring" -> android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
      "alarm" -> android.media.AudioAttributes.USAGE_ALARM
      "accessibility" -> android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
      else -> android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION // voice_call: legacy STREAM_VOICE_CALL below
    }
    // A gated 1 kHz tone (200 ms on / 200 ms off) is unmistakable on the far end.
    val n = rate * secs
    val pcm = ShortArray(n)
    for (i in 0 until n) {
      val on = (i / (rate / 5)) % 2 == 0
      pcm[i] = if (on) (Math.sin(2 * Math.PI * 1000 * i / rate) * gain * Short.MAX_VALUE).toInt().toShort() else 0
    }
    var track: android.media.AudioTrack? = null
    return try {
      val attrs = android.media.AudioAttributes.Builder().setUsage(usage).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .apply {
          // legacy STREAM_VOICE_CALL is the historical "into the call" path; force it for stream=voice_call.
          if (stream == "voice_call") setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
        }.build()
      val fmt = android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
      track = android.media.AudioTrack(attrs, fmt, pcm.size * 2, android.media.AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
      val wrote = track.write(pcm, 0, pcm.size)
      track.play()
      Thread.sleep(secs * 1000L + 200)
      mapOf("played" to true, "usage" to usage, "samples_written" to wrote, "seconds" to secs, "note" to "ask the far party if they heard a beeping tone")
    } catch (t: Throwable) {
      mapOf("played" to false, "error" to "${t.javaClass.simpleName}: ${t.message}")
    } finally {
      runCatching { track?.stop() }; runCatching { track?.release() }
    }
  }

  /**
   * Enumerate output audio devices. The one that matters is [AudioDeviceInfo.TYPE_TELEPHONY] (18):
   * its presence is what makes uplink injection possible (see [injectRoutedCapability]).
   */
  fun devicesCapability(context: Context): Capability = Capability(
    name = "debug.audio_devices",
    description = "RESEARCH (debug). List output AudioDeviceInfo entries. Reports whether TYPE_TELEPHONY (18) — the uplink sink — is present.",
    params = emptyList(),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { _, _ ->
    val am = context.getSystemService(AudioManager::class.java)
    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    CapabilityResult.ok(
      mapOf(
        "telephony_present" to outs.any { it.type == TYPE_TELEPHONY },
        "count" to outs.size,
        "devices" to outs.map { mapOf("type" to it.type, "name" to it.productName?.toString(), "sink" to (it.type == TYPE_TELEPHONY)) },
      ),
    )
  }

  /**
   * Uplink injection the way the Google Dialer does it (decompiled from com.google.android.dialer):
   * find the [AudioDeviceInfo.TYPE_TELEPHONY] output and `AudioTrack.setPreferredDevice()` onto it.
   * Every API here is public — the gate is the privileged `MODIFY_AUDIO_ROUTING` permission, without
   * which the routing request is silently ignored and the tone just plays out the local speaker.
   */
  fun injectRoutedCapability(context: Context): Capability = Capability(
    name = "debug.audio_inject_routed",
    description = "RESEARCH (debug). Inject a tone into the call UPLINK by routing an AudioTrack to the TYPE_TELEPHONY device (the Google Dialer's mechanism). Run during an active call and ask the far party.",
    params = listOf(
      Param("seconds", "integer", "Tone length (default 4, max 10)"),
      Param("gain", "number", "0..1 amplitude (default 0.7)"),
      Param("usage", "integer", "AudioAttributes usage (default 2 = VOICE_COMMUNICATION, as the Google dialer uses)"),
      Param("mode", "string", "AudioTrack transfer mode: stream (default, what the dialer uses) or static", enum = listOf("stream", "static")),
      Param("preferred", "boolean", "setPreferredDevice() onto the TYPE_TELEPHONY sink (default true)"),
      Param("mic_mute", "boolean", "AudioManager.setMicrophoneMute(true) for the duration (the dialer does this)"),
      Param("hal_params", "string", "Raw AudioManager.setParameters() before playing, e.g. 'direction=tx;device_mute=false;'"),
      Param("hal_params_after", "string", "Raw AudioManager.setParameters() after playing (to undo hal_params)"),
      Param("rate", "integer", "Sample rate (default 16000; telephony is often 8000)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, _ ->
    val secs = ((args["seconds"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)
    val gain = ((args["gain"] as? Number)?.toDouble() ?: 0.7).coerceIn(0.0, 1.0)
    val usage = (args["usage"] as? Number)?.toInt() ?: android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
    val rate = (args["rate"] as? Number)?.toInt() ?: 16_000
    val static = args["mode"]?.toString() == "static"
    val preferred = args["preferred"] as? Boolean ?: true
    val micMute = args["mic_mute"] as? Boolean ?: false
    val halBefore = args["hal_params"]?.toString()
    val halAfter = args["hal_params_after"]?.toString()
    val am = context.getSystemService(AudioManager::class.java)
    val granted = context.checkSelfPermission(PERM_MODIFY_AUDIO_ROUTING) == PackageManager.PERMISSION_GRANTED
    val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == TYPE_TELEPHONY }
    if (preferred && telephony == null) {
      CapabilityResult.ok(mapOf("played" to false, "failure" to "no_telephony_device", "modify_audio_routing_granted" to granted))
    } else {
      val r = withContext(Dispatchers.IO) {
        injectRouted(am, telephony, secs, gain, usage, rate, static, preferred, micMute, halBefore, halAfter)
      }
      CapabilityResult.ok(r + mapOf("modify_audio_routing_granted" to granted, "audio_mode" to modeName(am.mode)))
    }
  }

  private fun injectRouted(
    am: AudioManager, sink: AudioDeviceInfo?, secs: Int, gain: Double, usage: Int, rate: Int,
    static: Boolean, preferred: Boolean, micMute: Boolean, halBefore: String?, halAfter: String?,
  ): Map<String, Any?> {
    val n = rate * secs
    val pcm = ShortArray(n)
    for (i in 0 until n) {
      val on = (i / (rate / 5)) % 2 == 0 // 200 ms gated 1 kHz — unmistakable on the far end
      pcm[i] = if (on) (Math.sin(2 * Math.PI * 1000 * i / rate) * gain * Short.MAX_VALUE).toInt().toShort() else 0
    }
    var track: android.media.AudioTrack? = null
    val steps = mutableListOf<String>()
    return try {
      halBefore?.takeIf { it.isNotBlank() }?.let { runCatching { am.setParameters(it) }.onSuccess { _ -> steps += "setParameters($halBefore)" }.onFailure { e -> steps += "setParameters FAILED ${e.message}" } }
      if (micMute) runCatching { am.setMicrophoneMute(true) }.onSuccess { steps += "micMute(true)" }
      val attrs = android.media.AudioAttributes.Builder().setUsage(usage)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
      val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
      val b = android.media.AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
        .setBufferSizeInBytes(pcm.size * 2)
      if (static) b.setTransferMode(android.media.AudioTrack.MODE_STATIC)
      track = b.build()
      val routed = if (preferred && sink != null) track.setPreferredDevice(sink).also { steps += "setPreferredDevice=$it" } else null
      val before = track.routedDevice?.let { deviceName(it) }
      var wrote: Int
      if (static) {
        wrote = track.write(pcm, 0, pcm.size); track.play()
      } else {
        // MODE_STREAM, as the dialer uses: play first, then blocking writes.
        track.play(); wrote = track.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_BLOCKING)
      }
      val duringRoute = track.routedDevice?.let { deviceName(it) }
      Thread.sleep(300)
      mapOf(
        "played" to true, "routed_ok" to routed, "mode" to if (static) "static" else "stream",
        "usage" to usage, "rate" to rate, "sink" to sink?.let { deviceName(it) },
        "routed_before_play" to before, "routed_during_play" to duringRoute,
        "reached_telephony" to (duringRoute?.contains("type=$TYPE_TELEPHONY") ?: false),
        "samples_written" to wrote, "steps" to steps,
      )
    } catch (t: Throwable) {
      mapOf("played" to false, "failure" to t.javaClass.simpleName, "error" to t.message, "steps" to steps)
    } finally {
      runCatching { track?.stop() }; runCatching { track?.release() }
      if (micMute) runCatching { am.setMicrophoneMute(false) }
      halAfter?.takeIf { it.isNotBlank() }?.let { runCatching { am.setParameters(it) } }
    }
  }

  /**
   * Uplink injection via the **AudioPolicy / AudioMix** layer — the mechanism the Google Dialer actually
   * uses (its `AudioSystemApiUtil` exposes createAudioPolicy / createAudioMixingRule / addMix / addMixRule
   * / excludeMixRule alongside getClassWithName + invokeMethod, i.e. it drives these hidden `@SystemApi`
   * classes by reflection). `setPreferredDevice()` is only a routing *hint* and the policy manager ignores
   * it for the telephony sink; registering a mix with ROUTE_FLAG_RENDER at the TYPE_TELEPHONY device is
   * what actually renders our audio into the call's transmit path.
   *
   * `AudioManager.registerAudioPolicy()` is gated by MODIFY_AUDIO_ROUTING, which we hold as a privapp.
   * Every step is reported so a failure localises to an exact reflective call.
   */
  fun injectPolicyCapability(context: Context): Capability = Capability(
    name = "debug.audio_inject_policy",
    description = "RESEARCH (debug). Inject a tone into the call UPLINK by registering an AudioPolicy whose AudioMix renders at the TYPE_TELEPHONY device (the Google Dialer's real mechanism). Run during an active call.",
    params = listOf(
      Param("seconds", "integer", "Tone length (default 4, max 10)"),
      Param("gain", "number", "0..1 amplitude (default 0.8)"),
      Param("usage", "integer", "AudioAttributes usage for the track and the match rule (default 2 = VOICE_COMMUNICATION)"),
      Param("rule", "string", "Mix match rule: usage (RULE_MATCH_ATTRIBUTE_USAGE) or uid (RULE_MATCH_UID, our own)", enum = listOf("usage", "uid")),
      Param("route_flags", "integer", "AudioMix route flags: 1=RENDER (default), 2=LOOP_BACK, 3=both"),
      Param("rate", "integer", "Sample rate for track + mix (default 16000; telephony is often 8000)"),
      Param("mic_mute", "boolean", "setMicrophoneMute(true) for the duration so only injected audio goes out"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, _ ->
    val secs = ((args["seconds"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)
    val gain = ((args["gain"] as? Number)?.toDouble() ?: 0.8).coerceIn(0.0, 1.0)
    val usage = (args["usage"] as? Number)?.toInt() ?: android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
    val rate = (args["rate"] as? Number)?.toInt() ?: 16_000
    val routeFlags = (args["route_flags"] as? Number)?.toInt() ?: ROUTE_FLAG_RENDER
    val byUid = args["rule"]?.toString() == "uid"
    val micMute = args["mic_mute"] as? Boolean ?: false
    val am = context.getSystemService(AudioManager::class.java)
    val granted = context.checkSelfPermission(PERM_MODIFY_AUDIO_ROUTING) == PackageManager.PERMISSION_GRANTED
    val sink = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == TYPE_TELEPHONY }
    if (sink == null) {
      CapabilityResult.ok(mapOf("played" to false, "failure" to "no_telephony_device", "modify_audio_routing_granted" to granted))
    } else {
      val r = withContext(Dispatchers.IO) { injectViaPolicy(context, am, sink, secs, gain, usage, rate, routeFlags, byUid, micMute) }
      CapabilityResult.ok(r + mapOf("modify_audio_routing_granted" to granted, "audio_mode" to modeName(am.mode)))
    }
  }

  private fun injectViaPolicy(
    context: Context, am: AudioManager, sink: AudioDeviceInfo, secs: Int, gain: Double,
    usage: Int, rate: Int, routeFlags: Int, byUid: Boolean, micMute: Boolean,
  ): Map<String, Any?> {
    val steps = mutableListOf<String>()
    var policy: Any? = null
    var registered = false
    var track: android.media.AudioTrack? = null
    fun step(s: String) { steps += s }
    return try {
      val attrs = android.media.AudioAttributes.Builder().setUsage(usage)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
      val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

      // 1. AudioMixingRule — what audio the mix captures.
      val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
      val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
      if (byUid) {
        ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
          .invoke(ruleBuilder, RULE_MATCH_UID, android.os.Process.myUid())
        step("addMixRule(RULE_MATCH_UID, uid=${android.os.Process.myUid()})")
      } else {
        ruleBuilderCls.getMethod("addRule", android.media.AudioAttributes::class.java, Int::class.javaPrimitiveType)
          .invoke(ruleBuilder, attrs, RULE_MATCH_ATTRIBUTE_USAGE)
        step("addRule(usage=$usage, RULE_MATCH_ATTRIBUTE_USAGE)")
      }
      val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)
      step("AudioMixingRule built")

      // 2. AudioMix — render it AT the telephony device.
      val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
      val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
      val mixBuilder = mixBuilderCls.getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule")).newInstance(rule)
      mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, fmt)
      mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, routeFlags)
      step("setRouteFlags($routeFlags)")
      mixBuilderCls.getMethod("setDevice", AudioDeviceInfo::class.java).invoke(mixBuilder, sink)
      step("setDevice(${deviceName(sink)})")
      val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)
      step("AudioMix built")

      // 3. AudioPolicy + register (this is what MODIFY_AUDIO_ROUTING gates).
      val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
      val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(context)
      policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, mix)
      policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)
      step("AudioPolicy built")
      val reg = AudioManager::class.java
        .getMethod("registerAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
        .invoke(am, policy) as Int
      registered = reg == 0
      step("registerAudioPolicy -> $reg ${if (registered) "(SUCCESS)" else "(FAILED)"}")
      if (!registered) return mapOf("played" to false, "failure" to "register_audio_policy_failed", "code" to reg, "steps" to steps)

      // 4. Play — the registered mix should render this into the uplink.
      if (micMute) runCatching { am.setMicrophoneMute(true) }.onSuccess { step("micMute(true)") }
      val n = rate * secs
      val pcm = ShortArray(n)
      for (i in 0 until n) {
        val on = (i / (rate / 5)) % 2 == 0
        pcm[i] = if (on) (Math.sin(2 * Math.PI * 1000 * i / rate) * gain * Short.MAX_VALUE).toInt().toShort() else 0
      }
      track = android.media.AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
        .setBufferSizeInBytes(maxOf(pcm.size * 2, android.media.AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
      track.play()
      val wrote = track.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_BLOCKING)
      val routed = track.routedDevice?.let { deviceName(it) }
      step("wrote=$wrote routed=$routed")
      Thread.sleep(300)
      mapOf(
        "played" to true, "registered" to true, "rule" to if (byUid) "uid" else "usage",
        "route_flags" to routeFlags, "rate" to rate, "usage" to usage,
        "routed_during_play" to routed, "reached_telephony" to (routed?.contains("type=$TYPE_TELEPHONY") ?: false),
        "samples_written" to wrote, "steps" to steps,
        "note" to "ask the far party if they heard a beeping tone",
      )
    } catch (t: Throwable) {
      mapOf("played" to false, "failure" to t.javaClass.simpleName, "error" to (t.cause?.message ?: t.message), "steps" to steps)
    } finally {
      runCatching { track?.stop() }; runCatching { track?.release() }
      if (micMute) runCatching { am.setMicrophoneMute(false) }
      if (registered && policy != null) runCatching {
        AudioManager::class.java.getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy")).invoke(am, policy)
      }
    }
  }

  private const val TYPE_TELEPHONY = 18 // AudioDeviceInfo.TYPE_TELEPHONY
  private const val PERM_MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
  private const val ROUTE_FLAG_RENDER = 0x1 // AudioMix.ROUTE_FLAG_RENDER
  private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1 // AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE
  private const val RULE_MATCH_UID = 0x4 // AudioMixingRule.RULE_MATCH_UID

  private fun deviceName(d: AudioDeviceInfo) = "${d.productName} type=${d.type}"
  private fun modeName(m: Int) = when (m) {
    AudioManager.MODE_NORMAL -> "NORMAL"; AudioManager.MODE_RINGTONE -> "RINGTONE"; AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"; AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"; else -> "mode_$m"
  }

  fun hasPermission(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
