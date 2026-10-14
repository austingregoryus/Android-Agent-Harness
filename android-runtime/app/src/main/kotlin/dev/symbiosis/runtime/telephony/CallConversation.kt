package dev.symbiosis.runtime.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.Topics
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A live, near-real-time conversation on a carrier call: listen to the far party, transcribe them as they
 * speak, and talk back into the uplink.
 *
 * Both halves are on-device:
 *  - **hearing** — `VOICE_DOWNLINK` capture, which only yields audio because our [SymbiosisInCallService] is
 *    bound as a call companion (`CONTROL_INCALL_EXPERIENCE`) and we hold `CAPTURE_AUDIO_OUTPUT`;
 *  - **speaking** — [TelephonyAudio.Session], an audio policy that renders at the `TYPE_TELEPHONY` device.
 *
 * Turn-taking, which is what makes it feel like a conversation or a form being read aloud:
 *  - recognition streams *while* the person talks, so the transcript is ready almost as soon as they stop;
 *  - a turn ends when the person *sounds* finished ([TurnDetector]) after a short pause, not after a fixed
 *    silence — a confident "done" closes the turn at [minSilenceMs] (400 ms), an unsure one — or one whose words end
 *    mid-thought — waits up to [maxSilenceMs];
 *  - the far party talking over us only counts as an interruption once it has lasted [bargeInMinMs]: a cough
 *    or an "uh-huh" while we speak is not a request to stop;
 *  - the audio policy is registered once per call, not per utterance, and playback is written in 100 ms
 *    chunks, which is also the barge-in reaction time.
 *
 * The runtime deliberately does not decide *what* to say — it emits [Topics.CALL_TRANSCRIPT] and the agent
 * replies by calling `telephony.speak`. That keeps the phone a capability substrate and the model the brain,
 * which is the whole architecture.
 */
class CallConversation(
  private val context: Context,
  private val broker: EventBroker,
  private val sessionId: String?,
) {

  enum class State { IDLE, LISTENING, SPEAKING }

  /** How a turn is closed. */
  enum class Endpoint { MODEL, TIMER }

  private val am = context.getSystemService(AudioManager::class.java)
  private val running = AtomicBoolean(false)
  /** Resources released exactly once, whether by [stop] or by [start] failing; [stop] may run after the loop died. */
  private val tornDown = AtomicBoolean(false)
  /**
   * Serialises utterances. `telephony.speak` and `telephony.play_audio` run on separate request threads, and two
   * of them at once used to interleave on the one uplink: [TelephonyAudio.Session.play] was not reentrant (one
   * track field, one cancel flag), the SPEAKING state flipped back to LISTENING under the other utterance, and
   * the far party heard both. The second caller now waits its turn.
   */
  private val speakLock = Any()
  private val state = AtomicReference(State.IDLE)
  private var audio: TelephonyAudio.Session? = null
  private var thread: Thread? = null
  private val asr = StreamingAsr(context)
  private var detector: TurnDetector? = null
  /** One thread for the end-of-turn model, so a ~90 ms inference never stalls the capture loop. */
  private var detectorExec: java.util.concurrent.ExecutorService? = null
  @Volatile private var bargeInEnabled = true
  @Volatile private var muteMicForCall = true
  /** Far-party speech must last this long while we are talking before we stop for it. */
  @Volatile private var bargeInMinMs = 500
  /** Shortest pause before a turn may close; the end-of-turn model can only extend it. 200 ms closed turns on
   *  ordinary mid-sentence pauses on real calls (and made the agent say "okay" over people). */
  @Volatile private var minSilenceMs = 400
  /** Ceiling on the pause: the turn closes here even if the model still thinks they are mid-sentence. */
  @Volatile private var maxSilenceMs = 900
  @Volatile private var endpoint = Endpoint.MODEL
  @Volatile private var micMuted = false
  @Volatile private var lastError: String? = null
  /** Latest interim transcript of the open turn — the words matter for endpointing, not just the sound. */
  @Volatile private var lastPartial: String = ""

  /** The open uplink, so audio synthesized elsewhere can render through the same registered policy. */
  val uplink: TelephonyAudio.Session? get() = audio

  val currentState: State get() = state.get()
  val isRunning: Boolean get() = running.get()
  val error: String? get() = lastError
  val endpointMode: Endpoint get() = endpoint

  /**
   * Open the uplink and start listening. Requires an active call.
   *
   * [hangover] is the longest pause tolerated before a turn closes. With the end-of-turn model available the
   * turn usually closes much sooner than that; without it (or with [useModel] false) it is the fixed wait.
   */
  fun start(
    bargeIn: Boolean = true, muteMic: Boolean = true, hangover: Int = 900,
    useModel: Boolean = true, minSilence: Int = 400, bargeInMin: Int = 500,
  ): Result<Unit> {
    if (!running.compareAndSet(false, true)) return Result.failure(IllegalStateException("already running"))
    bargeInEnabled = bargeIn
    muteMicForCall = muteMic
    maxSilenceMs = hangover.coerceIn(200, 2000)
    minSilenceMs = minSilence.coerceIn(100, maxSilenceMs)
    bargeInMinMs = bargeInMin.coerceIn(0, 2000)
    detector = if (useModel) {
      TurnDetector.load(context).onFailure {
        Log.w(TAG, "end-of-turn model unavailable, using a fixed ${maxSilenceMs}ms hangover: $it")
      }.getOrNull()
    } else null
    endpoint = if (detector != null) Endpoint.MODEL else Endpoint.TIMER
    val opened = TelephonyAudio.Session.open(context, am)
    if (opened.isFailure) {
      running.set(false)
      // Only the model exists yet. The executor is created after the uplink opens, so a failed start can no longer
      // leak its thread (it used to be created first and was never shut down on this path).
      runCatching { detector?.close() }; detector = null
      tornDown.set(true)
      return Result.failure(opened.exceptionOrNull() ?: IllegalStateException("could not open uplink"))
    }
    detectorExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
      Thread(r, "symbiosis-turn-detector").apply { isDaemon = true }
    }
    audio = opened.getOrNull()
    // The mute is applied by the capture loop when a call actually goes active, not here. Muting at attach
    // time would silence a call the user placed themselves, and the system clears the mute at the end of every
    // call anyway — so it has to be per-call to be correct.
    state.set(State.LISTENING)
    thread = Thread({ captureLoop() }, "symbiosis-conversation").apply { isDaemon = true; start() }
    return Result.success(Unit)
  }

  /**
   * Stop and release everything. Idempotent, and it still releases when the loop already died on its own
   * (running is false by then, which used to make this return without closing the model or the executor).
   */
  fun stop() {
    running.set(false)
    if (!tornDown.compareAndSet(false, true)) return
    // Unmute FIRST and unconditionally: leaving the user's microphone muted after the call would be a nasty,
    // silent failure they would only discover on their next call.
    unmuteMic()
    runCatching { asr.cancel() }
    val t = thread
    runCatching { t?.join(1500) }
    thread = null
    synchronized(uplinkLock) { runCatching { audio?.close() }; audio = null }
    // Order matters for the end-of-turn model: stop the executor and let an in-flight inference finish BEFORE
    // closing the ONNX session. Closing it first (as this used to) could free the native session under a running
    // ~90 ms inference: a native crash, not an exception runCatching could catch.
    detectorExec?.let { ex ->
      runCatching { ex.shutdownNow() }
      runCatching { ex.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
    detectorExec = null
    // The capture thread can also run the model inline (askModel's fallback). If it is somehow still alive after
    // the join, leak the model rather than close it under that thread.
    if (t == null || !t.isAlive) runCatching { detector?.close() }
    else Log.w(TAG, "capture thread still alive after join; leaving the end-of-turn model open")
    detector = null
    state.set(State.IDLE)
  }

  /** Guards [audio] being closed/nulled against a [speak] that is about to use it. Never held while playing. */
  private val uplinkLock = Any()

  private fun unmuteMic() {
    if (!micMuted) return
    runCatching { am.isMicrophoneMute = false }
    micMuted = false
  }

  /** Speak into the uplink. Blocks for the utterance; returns early if the far party barges in. */
  fun speak(pcm: ShortArray): TelephonyAudio.PlayResult? = synchronized(speakLock) {
    // Checked under the lock: a caller queued behind another utterance may find the loop gone by the time it gets
    // the uplink, and playing through a policy that is being unregistered is not a reply anyone hears.
    val a = synchronized(uplinkLock) { if (running.get()) audio else null } ?: return null
    state.set(State.SPEAKING)
    broker.emit(Topics.CALL_SPEAKING, mapOf("speaking" to true, "session_id" to sessionId, "samples" to pcm.size))
    return try {
      a.play(pcm, muteMic = false)
    } finally {
      state.set(if (running.get()) State.LISTENING else State.IDLE)
      broker.emit(Topics.CALL_SPEAKING, mapOf("speaking" to false, "session_id" to sessionId))
    }
  }

  /** Cut the current utterance short (explicit barge-in / cancel). */
  fun stopSpeaking() { audio?.stop() }

  /** One closed far-party turn's audio (16 kHz mono, pre-roll included), for an agent-side recognizer. */
  class TurnRecord(val id: Int, val pcm: ShortArray, val closedAt: Long)

  private val nextTurnId = java.util.concurrent.atomic.AtomicInteger(0)
  private val recentTurns = ArrayDeque<TurnRecord>()

  /** The audio of [id] (or the most recently closed turn when null), if it is still held. */
  fun turnAudio(id: Int?): TurnRecord? = synchronized(recentTurns) {
    if (id == null) recentTurns.lastOrNull() else recentTurns.firstOrNull { it.id == id }
  }

  /** The far party's current turn, kept for the end-of-turn model (only the last window matters). */
  private class TurnAudio(private val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var write = 0
    var size = 0; private set
    fun clear() { write = 0; size = 0 }
    fun append(frame: ShortArray, n: Int) {
      for (i in 0 until n) { buf[write] = frame[i]; write = (write + 1) % capacity }
      size = minOf(capacity, size + n)
    }
    /** Oldest-first copy of what is held. */
    fun snapshot(): ShortArray {
      val out = ShortArray(size)
      val start = (write - size + capacity) % capacity
      for (i in 0 until size) out[i] = buf[(start + i) % capacity]
      return out
    }
  }

  @SuppressLint("MissingPermission")
  private fun captureLoop() {
    val rate = TelephonyAudio.RATE
    var rec: AudioRecord? = null
    try {
      // Every early exit is inside the try, so the finally below always runs its "died on its own" handling.
      val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf <= 0) { lastError = "getMinBufferSize=$minBuf"; return }
      rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_DOWNLINK, rate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate),
      )
      if (rec.state != AudioRecord.STATE_INITIALIZED) { lastError = "AudioRecord state=${rec.state}"; return }
      rec.startRecording()
      // The VAD's own hangover is only the *first* pause worth asking about. In MODEL mode the turn stays
      // open past it until the model says the person is done (or maxSilenceMs runs out); in TIMER mode the
      // VAD's END is the end.
      // TIMER mode (model off or failed to load) is the old fixed wait; the 900 ms ceiling meant for the model
      // path would be pure dead air there, so it uses the 500 ms that worked on the earlier live calls.
      val vad = Vad(
        sampleRate = rate,
        hangoverMs = if (endpoint == Endpoint.MODEL) minSilenceMs else minOf(maxSilenceMs, TIMER_HANGOVER_MS),
      )
      val frame = ShortArray(rate / 50) // 20 ms
      val turnAudio = TurnAudio(TurnDetector.WINDOW_SAMPLES)
      // The whole turn (pre-roll included), kept for `telephony.turn_audio` so an agent can run its own
      // recognizer on it after the close event. Capped; a turn longer than this keeps only its start.
      var fullTurn = ShortArray(FULL_TURN_MAX_SAMPLES)
      var fullTurnLen = 0
      var turnId = 0

      /** Every frame of a turn goes to the recognizer, the end-of-turn window and the full-turn record. */
      fun capture(f: ShortArray, n: Int) {
        asr.feed(f, n)
        turnAudio.append(f, n)
        val room = fullTurn.size - fullTurnLen
        if (room > 0) {
          val k = minOf(n, room)
          System.arraycopy(f, 0, fullTurn, fullTurnLen, k)
          fullTurnLen += k
        }
      }
      var turnStarted = 0L
      var turnOpen = false
      var wasInCall = false
      var wasSpeaking = false
      // MODEL mode: the VAD has gone quiet on an open turn and we are deciding whether it is over.
      var pendingSinceMs = -1L   // when the current pause began
      var lastCheckMs = -1L
      var checks = 0
      // The end-of-turn model runs on its own thread (~90 ms per check): the capture thread must keep
      // reading frames, feeding the recognizer and watching for the far party to resume meanwhile.
      var check: java.util.concurrent.Future<Float?>? = null
      // While we speak: when the far party's current burst of speech started, if it has not yet lasted
      // long enough to count as an interruption.
      var interruptCandidateMs = -1L

      // Pre-roll. The VAD needs ~120 ms of sustained speech before it will open a turn (so clicks and line
      // pops do not), but those frames are the speaker's first syllable. Without this ring buffer the
      // recognizer never sees them and "I'd like to book a table" arrives as "Like to book a table". 600 ms
      // (Pipecat's default is 500): with 300, a soft fricative-initial word the energy VAD opened late on
      // ("seven twenty-three") arrived as "twenty-three" four times on one call.
      val preRollFrames = PRE_ROLL_MS / 20
      val preRoll = arrayOfNulls<ShortArray>(preRollFrames)
      var preRollAt = 0
      fun rememberPreRoll(f: ShortArray, n: Int) {
        preRoll[preRollAt] = f.copyOf(n)
        preRollAt = (preRollAt + 1) % preRollFrames
      }
      fun flushPreRoll() {
        for (i in 0 until preRollFrames) {
          val f = preRoll[(preRollAt + i) % preRollFrames] ?: continue
          capture(f, f.size)
        }
        java.util.Arrays.fill(preRoll, null)
      }

      fun openTurn() {
        turnOpen = true
        turnStarted = System.currentTimeMillis()
        pendingSinceMs = -1; lastCheckMs = -1; checks = 0
        check?.cancel(false); check = null
        turnAudio.clear()
        fullTurnLen = 0
        turnId = nextTurnId.incrementAndGet()
        val thisTurn = turnId
        broker.emit(
          Topics.CALL_LISTENING,
          mapOf("session_id" to sessionId, "listening" to true, "turn_id" to thisTurn, "rms" to vad.lastRms.toInt(), "while_speaking" to (state.get() == State.SPEAKING)),
        )
        lastPartial = ""
        asr.start(
          onPartial = { text ->
            lastPartial = text
            broker.emit(
              Topics.CALL_TRANSCRIPT,
              mapOf("session_id" to sessionId, "text" to text, "final" to false, "turn_id" to thisTurn),
            )
          },
          onFinal = { text ->
            // A turn the recognizer could not resolve is line noise, not something the agent should be
            // asked to reply to; emitting it would make every consumer filter empty strings.
            if (!text.isNullOrBlank()) {
              broker.emit(
                Topics.CALL_TRANSCRIPT,
                mapOf(
                  "session_id" to sessionId, "text" to text, "final" to true, "turn_id" to thisTurn,
                  "speech_ms" to vad.lastTurnMs,
                  "latency_ms" to (System.currentTimeMillis() - turnStarted),
                ),
              )
            }
          },
        )
        flushPreRoll() // the onset the VAD needed to decide this was speech
      }

      fun closeTurn(how: String, p: Float?, silenceMs: Long) {
        turnOpen = false
        pendingSinceMs = -1
        check?.cancel(false); check = null
        asr.finish() // closes the pipe; the final result arrives on the listener
        val payload = HashMap<String, Any?>()
        payload["session_id"] = sessionId; payload["listening"] = false
        payload["endpoint"] = how; payload["silence_ms"] = silenceMs; payload["checks"] = checks
        // Keep the turn's audio for `telephony.turn_audio` and say how much there is, so a driver that
        // runs its own recognizer can fetch it the moment the turn closes, before our final arrives.
        payload["turn_id"] = turnId; payload["audio_ms"] = fullTurnLen * 1000L / TelephonyAudio.RATE
        synchronized(recentTurns) {
          recentTurns.addLast(TurnRecord(turnId, fullTurn.copyOf(fullTurnLen), System.currentTimeMillis()))
          while (recentTurns.size > RECENT_TURNS) recentTurns.removeFirst()
        }
        if (p != null) payload["p_complete"] = p
        broker.emit(Topics.CALL_LISTENING, payload)
      }

      /** Ask the end-of-turn model about the turn so far, off this thread. */
      fun askModel(det: TurnDetector, now: Long) {
        val snap = turnAudio.snapshot()
        checks++; lastCheckMs = now
        val exec = detectorExec
        check = runCatching { exec!!.submit<Float?> { runCatching { det.probability(snap) }.getOrNull() } }
          .getOrElse {
            // No executor (shutting down): answer inline rather than never.
            java.util.concurrent.CompletableFuture.completedFuture(runCatching { det.probability(snap) }.getOrNull())
          }
      }

      /** Consume a finished check: close the turn if the person sounds — and reads — finished. */
      fun consumeCheck(silence: Long) {
        val c = check ?: return
        if (!c.isDone) return
        check = null
        val p = runCatching { c.get() }.getOrNull()
        if (p == null) closeTurn("timer", null, silence)
        else if (p >= COMPLETE_THRESHOLD && !looksUnfinished(lastPartial)) closeTurn("model", p, silence)
      }

      while (running.get()) {
        val n = rec.read(frame, 0, frame.size)
        if (n <= 0) { Thread.sleep(5); continue }

        // Only endpoint turns while a call is actually up. Attaching the loop before the call connects used
        // to open a turn on dead air; that stale turn then swallowed the far party's first words and produced
        // a duplicate transcript when the next turn opened, which made the agent answer twice and talk over
        // itself. No call, no turns. The audio mode alone is not enough: it is already IN_CALL while an
        // outgoing call is still ringing, so ringback tone opened turns. When our InCallService is bound it
        // knows the precise state, and only ACTIVE counts.
        val inCall = am.mode == AudioManager.MODE_IN_CALL && preciseActiveOrUnknown()
        if (!inCall) {
          if (wasInCall) {
            // The call ended. Android drops our mic mute with it, so forget we hold it — the next call must
            // mute again from scratch rather than assume the previous mute is still in force.
            micMuted = false
            wasInCall = false
          }
          vad.reset()
          if (turnOpen) { turnOpen = false; runCatching { asr.cancel() } }
          java.util.Arrays.fill(preRoll, null)
          continue
        }
        if (!wasInCall) {
          // A call just went active. Re-apply the mute HERE, not once at conversation start: the system clears
          // it at the end of every call, so a conversation that outlives one call would leave the microphone
          // live for the next one and transmit the room.
          wasInCall = true
          if (muteMicForCall && !micMuted) {
            runCatching { am.isMicrophoneMute = true }.onSuccess { micMuted = true }
          }
        }

        val now = System.currentTimeMillis()
        val speaking = state.get() == State.SPEAKING
        if (speaking && !wasSpeaking && bargeInEnabled && vad.state == Vad.State.SPEECH) {
          // We just started talking while THEY were already talking (they began in the gap between two of
          // our sentences, when we were not "speaking"). Interruptions used to arm only for speech that
          // started during playback, so this case talked straight through the person. Arm it now: if they
          // keep going for bargeInMinMs we stop, exactly as if they had started over us.
          if (bargeInMinMs <= 0) {
            audio?.stop()
            broker.emit(Topics.CALL_BARGE_IN, mapOf("session_id" to sessionId, "rms" to vad.lastRms.toInt(), "ongoing" to true))
          } else interruptCandidateMs = now
        }
        wasSpeaking = speaking
        // While we talk, the downlink can carry a faint echo of our own voice from the far end. Raising the
        // bar for "speech" during playback keeps that from reading as an interruption; real speech on a
        // carrier downlink sits well above the threshold even so.
        when (vad.accept(frame, n, gain = if (speaking) 1.0 / SPEAKING_THRESHOLD_FACTOR else 1.0)) {
          Vad.Event.START -> {
            if (speaking && bargeInEnabled) {
              // Not an interruption yet. A listener's "mm-hm" or a cough is over in a few hundred ms; only
              // speech that keeps going means they want the floor.
              if (bargeInMinMs <= 0) {
                audio?.stop()
                broker.emit(Topics.CALL_BARGE_IN, mapOf("session_id" to sessionId, "rms" to vad.lastRms.toInt()))
              } else interruptCandidateMs = now
            }
            if (turnOpen) {
              // They resumed inside the pause we were weighing: same turn, keep feeding the recognizer. A
              // check still in flight is about a pause that is over; its answer must not close the turn.
              pendingSinceMs = -1
              check?.cancel(false); check = null
            } else openTurn()
            capture(frame, n)
          }
          Vad.Event.END -> {
            capture(frame, n)
            interruptCandidateMs = -1
            val det = detector
            if (endpoint == Endpoint.MODEL && det != null && turnOpen) {
              // First pause worth asking about. The pause is already minSilenceMs old (the VAD's hangover);
              // ask the model now and decide when it answers, while frames keep flowing.
              pendingSinceMs = now - minSilenceMs
              checks = 0
              askModel(det, now)
            } else if (turnOpen) {
              closeTurn("timer", null, maxSilenceMs.toLong())
            }
          }
          Vad.Event.NONE -> {
            if (vad.state == Vad.State.SPEECH) {
              if (turnOpen) { capture(frame, n) }
              if (interruptCandidateMs >= 0 && speaking && now - interruptCandidateMs >= bargeInMinMs) {
                interruptCandidateMs = -1
                audio?.stop()
                broker.emit(
                  Topics.CALL_BARGE_IN,
                  mapOf("session_id" to sessionId, "after_ms" to bargeInMinMs, "rms" to vad.lastRms.toInt()),
                )
              } else if (!speaking) interruptCandidateMs = -1
            } else if (turnOpen && pendingSinceMs >= 0) {
              // Still deciding. The recognizer hears the pause too, so a final that comes back covers it.
              capture(frame, n)
              val silence = now - pendingSinceMs
              val det = detector
              if (silence >= maxSilenceMs || det == null) {
                closeTurn("timeout", null, silence)
              } else if (vad.lastTurnMs < SHORT_TURN_MS && silence >= SHORT_TURN_CEILING_MS && !looksUnfinished(lastPartial)) {
                // "No." / "Yes." / "Bye." — the model rates a lone word as unfinished (it often is), but nobody
                // pauses for long inside a one-word answer. Don't make short replies wait the full ceiling.
                // Unless the words say otherwise: "What about" is a short burst too, and closing it here
                // is exactly the mid-sentence cut the dangling-word rule exists to prevent.
                closeTurn("short", null, silence)
              } else {
                consumeCheck(silence)
                if (turnOpen && check == null && now - lastCheckMs >= RECHECK_MS) askModel(det, now)
              }
            } else rememberPreRoll(frame, n)
          }
        }
      }
      vad.reset()
      if (turnOpen) { turnOpen = false; runCatching { asr.finish() } }
    } catch (t: Throwable) {
      lastError = "${t.javaClass.simpleName}: ${t.message}"
    } finally {
      runCatching { rec?.stop() }; runCatching { rec?.release() }
      // Still "running" here means nobody asked us to stop: the loop died on its own (AudioRecord would not
      // initialise, a capture error). It used to return with running still true, so status said "running", the
      // registry handed the dead loop back to `converse start`, and the uplink policy stayed registered with no
      // one listening. Now: flip the flag (Conversations then reaps and replaces it), never strand the mic muted,
      // release the uplink, and tell the driver, which would otherwise wait forever for a transcript.
      val diedOnItsOwn = running.getAndSet(false)
      unmuteMic()
      if (diedOnItsOwn) {
        state.set(State.IDLE)
        val a = synchronized(uplinkLock) { audio.also { audio = null } }
        runCatching { a?.close() } // stop() + unregister; a speak() in progress is cut at its next chunk
        Log.w(TAG, "conversation loop for $sessionId died: ${lastError ?: "unknown"}")
        runCatching {
          broker.emit(
            Topics.CALL_CONVERSATION,
            mapOf("session_id" to sessionId, "state" to "stopped", "reason" to "error", "error" to (lastError ?: "capture loop exited")),
          )
        }
      }
    }
  }

  /**
   * Is the call ACTIVE according to Telecom? Only answerable while our InCallService is bound (we are the
   * dialer); otherwise the audio mode is the best signal there is, and this says "unknown" = true.
   */
  private fun preciseActiveOrUnknown(): Boolean {
    val holder = SymbiosisInCallService.Holder
    if (!holder.bound || holder.calls.isEmpty()) return true
    return holder.calls.keys.any { it.state == android.telecom.Call.STATE_ACTIVE }
  }

  companion object {
    private const val TAG = "CallConversation"

    /**
     * Words a sentence does not end on. The acoustic model rated "What about" as 97% finished on a real call
     * (it scores nearly everything on carrier audio 0.86–0.99), the turn closed at the pause, and the far
     * party's "…seven twenty-three" arrived as a separate, mangled turn. The words know better than the sound
     * here: a turn whose transcript ends in a preposition, article, conjunction or dangling auxiliary waits
     * out the ceiling instead. Cheap, and exactly what a text end-of-turn model learns first.
     */
    private val DANGLING = setOf(
      "about", "at", "for", "to", "of", "with", "on", "in", "from", "by", "into", "than", "and", "or", "but",
      "so", "the", "a", "an", "my", "your", "our", "their", "is", "are", "was", "were", "do", "does", "did",
      "can", "could", "would", "should", "will", "have", "has", "had", "if", "when", "what", "which", "how",
      "that", "this", "these", "those", "um", "uh", "like", "just", "very", "really", "maybe", "around",
    )

    fun looksUnfinished(partial: String): Boolean {
      val last = partial.trim().trimEnd('.', ',', '?', '!').substringAfterLast(' ').lowercase()
      return last.isNotEmpty() && last in DANGLING
    }
    /** smart-turn's own decision boundary. */
    const val COMPLETE_THRESHOLD = 0.5f
    /** How often to re-ask the model during a pause it called "not done". */
    private const val RECHECK_MS = 200L
    /** A burst of speech shorter than this is a one-or-two-word reply… */
    private const val SHORT_TURN_MS = 900
    /** …and closes after this much silence regardless of what the model thinks. */
    private const val SHORT_TURN_CEILING_MS = 450L
    /** Fixed hangover when the end-of-turn model is not in use. */
    private const val TIMER_HANGOVER_MS = 500
    /** Audio kept from before the VAD opened a turn, fed to the recognizer first (the onset it needed). */
    const val PRE_ROLL_MS = 600
    /** Longest turn recorded in full for `telephony.turn_audio` (30 s at 16 kHz). */
    private const val FULL_TURN_MAX_SAMPLES = 30 * TelephonyAudio.RATE
    /** Closed turns whose audio is still fetchable. */
    private const val RECENT_TURNS = 4
    /** The VAD's enter threshold is effectively multiplied by this while the agent is speaking (echo guard). */
    private const val SPEAKING_THRESHOLD_FACTOR = 1.5
  }
}
