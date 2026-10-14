package dev.symbiosis.runtime

import dev.symbiosis.runtime.telephony.Vad
import dev.symbiosis.runtime.telephony.Wav
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpointing and resampling are the two pieces of the conversation loop that are pure logic, so they are the
 * two that can be pinned down without a phone call. Everything else (capture, ASR, uplink render) needs the
 * device and a live call.
 */
class ConversationAudioTest {

  private val rate = 16_000
  private val frame = rate / 50 // 20 ms, matching the capture loop

  private fun tone(ms: Int, amp: Double): ShortArray {
    val n = rate * ms / 1000
    return ShortArray(n) { (sin(2 * Math.PI * 300 * it / rate) * amp * Short.MAX_VALUE).toInt().toShort() }
  }

  private fun silence(ms: Int) = ShortArray(rate * ms / 1000)

  /** Feed a buffer through the VAD in capture-sized frames, collecting transitions. */
  private fun feed(vad: Vad, buf: ShortArray): List<Vad.Event> {
    val events = mutableListOf<Vad.Event>()
    var i = 0
    while (i < buf.size) {
      val n = minOf(frame, buf.size - i)
      val f = buf.copyOfRange(i, i + n)
      val e = vad.accept(f, n)
      if (e != Vad.Event.NONE) events += e
      i += n
    }
    return events
  }

  @Test
  fun `speech opens and closes exactly one turn`() {
    val vad = Vad(sampleRate = rate)
    val events = feed(vad, silence(200) + tone(900, 0.3) + silence(1200))
    assertEquals(listOf(Vad.Event.START, Vad.Event.END), events)
    assertEquals(Vad.State.SILENCE, vad.state)
  }

  @Test
  fun `silence alone never opens a turn`() {
    val vad = Vad(sampleRate = rate)
    assertTrue(feed(vad, silence(3000)).isEmpty())
    assertEquals(Vad.State.SILENCE, vad.state)
  }

  @Test
  fun `a short click does not open a turn`() {
    // 40 ms of noise is below the 120 ms start threshold — line pops must not start a turn.
    val vad = Vad(sampleRate = rate)
    assertTrue(feed(vad, silence(100) + tone(40, 0.9) + silence(600)).isEmpty())
  }

  @Test
  fun `a pause inside a sentence does not split the turn`() {
    // 300 ms gap is shorter than the 700 ms hangover, so this is one turn, not two.
    val vad = Vad(sampleRate = rate)
    val events = feed(vad, tone(500, 0.3) + silence(300) + tone(500, 0.3) + silence(1200))
    assertEquals(listOf(Vad.Event.START, Vad.Event.END), events)
  }

  @Test
  fun `a long gap splits two turns`() {
    val vad = Vad(sampleRate = rate)
    val events = feed(vad, tone(400, 0.3) + silence(1300) + tone(400, 0.3) + silence(1300))
    assertEquals(listOf(Vad.Event.START, Vad.Event.END, Vad.Event.START, Vad.Event.END), events)
  }

  @Test
  fun `turn duration excludes the trailing hangover`() {
    val vad = Vad(sampleRate = rate)
    feed(vad, tone(1000, 0.3) + silence(1400))
    // ~1000 ms of speech; the 700 ms hangover must not be counted into it.
    assertTrue("lastTurnMs=${vad.lastTurnMs} should be ~1000", vad.lastTurnMs in 800..1200)
  }

  @Test
  fun `reset closes an open turn`() {
    val vad = Vad(sampleRate = rate)
    feed(vad, tone(400, 0.4))
    assertEquals(Vad.State.SPEECH, vad.state)
    assertEquals(Vad.Event.END, vad.reset())
    assertEquals(Vad.State.SILENCE, vad.state)
    assertEquals(Vad.Event.NONE, vad.reset()) // idempotent
  }

  @Test
  fun `quiet speech below the floor does not trigger`() {
    val vad = Vad(sampleRate = rate)
    assertTrue(feed(vad, tone(1000, 0.005)).isEmpty())
  }

  @Test
  fun `resample preserves duration and signal`() {
    // 24 kHz is what the Google TTS engine emits; we must land at 16 kHz for the uplink.
    val src = ShortArray(24_000) { (sin(2 * Math.PI * 200 * it / 24_000) * 0.5 * Short.MAX_VALUE).toInt().toShort() }
    val out = Wav.resample(src, 24_000, 16_000)
    assertEquals(16_000, out.size)
    assertTrue("resampled tone should retain energy, rms=${Vad.rms(out)}", Vad.rms(out) > 1000)
  }

  @Test
  fun `resample is a no-op at the same rate`() {
    val src = ShortArray(100) { it.toShort() }
    assertSame(src, Wav.resample(src, 16_000, 16_000))
  }

  @Test
  fun `resample handles empty and upsampling`() {
    assertEquals(0, Wav.resample(ShortArray(0), 8_000, 16_000).size)
    assertEquals(16_000, Wav.resample(ShortArray(8_000), 8_000, 16_000).size)
  }

  @Test
  fun `rms is zero for silence and scales with amplitude`() {
    assertEquals(0.0, Vad.rms(silence(100)), 0.001)
    val quiet = Vad.rms(tone(100, 0.1))
    val loud = Vad.rms(tone(100, 0.8))
    assertTrue("loud=$loud quiet=$quiet", loud > quiet * 4)
  }

  @Test
  fun `decoding a synthetic wav round-trips to pcm`() {
    // Build a minimal 24 kHz mono WAV the way a TTS engine would, and check we decode + resample it.
    val frames = 2400 // 100 ms
    val pcm = ShortArray(frames) { (sin(2 * Math.PI * 300 * it / 24_000) * 0.5 * Short.MAX_VALUE).toInt().toShort() }
    val data = ByteArray(frames * 2)
    for (i in 0 until frames) {
      data[i * 2] = (pcm[i].toInt() and 0xff).toByte()
      data[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xff).toByte()
    }
    val f = java.io.File.createTempFile("tts", ".wav")
    f.deleteOnExit()
    f.outputStream().use { o ->
      fun s(x: String) = o.write(x.toByteArray(Charsets.US_ASCII))
      fun i32(v: Int) = o.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
      fun i16(v: Int) = o.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
      s("RIFF"); i32(36 + data.size); s("WAVE")
      s("fmt "); i32(16); i16(1); i16(1); i32(24_000); i32(48_000); i16(2); i16(16)
      s("data"); i32(data.size); o.write(data)
    }
    val decoded = Wav.decodeMono16(f, 16_000)
    requireNotNull(decoded) { "WAV should decode" }
    assertEquals(24_000, decoded.sourceRate)
    assertEquals(1600, decoded.pcm.size) // 100 ms at 16 kHz
    assertTrue(Vad.rms(decoded.pcm) > 1000)
  }

  @Test
  fun `decoding a truncated file fails cleanly rather than throwing`() {
    val f = java.io.File.createTempFile("bad", ".wav")
    f.deleteOnExit()
    f.writeBytes(ByteArray(10))
    assertNull(Wav.decodeMono16(f, 16_000))
  }

  @Test
  fun `a transcript ending mid-thought reads as unfinished`() {
    // The words veto the sound: these closed turns on a live call because the acoustic model was "sure".
    for (s in listOf("What about", "a table for", "Is it for", "seven twenty and", "do you have a", "um", "what about,"))
      assertTrue(s, dev.symbiosis.runtime.telephony.CallConversation.looksUnfinished(s))
    for (s in listOf("What about seven", "Yes", "Does seven twenty work?", "No.", "", "   ", "twenty three p.m."))
      assertFalse(s, dev.symbiosis.runtime.telephony.CallConversation.looksUnfinished(s))
  }

  @Test
  fun `vad tracks a realistic alternating conversation`() {
    // Two turns with a natural mid-sentence pause in each; the loop must see exactly two.
    val vad = Vad(sampleRate = rate)
    val audio = silence(300) +
      tone(600, 0.35) + silence(250) + tone(400, 0.35) + silence(1100) +
      tone(500, 0.35) + silence(200) + tone(700, 0.35) + silence(1100)
    val events = feed(vad, audio)
    assertEquals(2, events.count { it == Vad.Event.START })
    assertEquals(2, events.count { it == Vad.Event.END })
    assertFalse(vad.state == Vad.State.SPEECH)
  }
}
