package dev.symbiosis.runtime

import dev.symbiosis.runtime.telephony.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The WAV reader takes caller-supplied bytes (telephony.play_audio): hostile headers must fail fast, never hang. */
class WavParserTest {

  private class W {
    val o = ByteArrayOutputStream()
    fun s(x: String) = apply { o.write(x.toByteArray(Charsets.US_ASCII)) }
    fun i32(v: Long) = apply { for (k in 0 until 4) o.write(((v shr (8 * k)) and 0xff).toInt()) }
    fun i16(v: Int) = apply { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
    fun bytes(b: ByteArray) = apply { o.write(b) }
    fun fmt(rate: Long = 16_000, channels: Int = 1, bits: Int = 16) =
      s("fmt ").i32(16).i16(1).i16(channels).i32(rate).i32(rate * channels * 2).i16(channels * 2).i16(bits)
    fun build() = o.toByteArray()
  }

  private fun riff() = W().s("RIFF").i32(0).s("WAVE")

  /** Fails the test instead of hanging the build: the bug was an infinite loop. */
  private fun decodeWithin(b: ByteArray, ms: Long = 2_000): Wav.Decoded? {
    var out: Wav.Decoded? = null
    val t = Thread { out = Wav.decodeMono16(b, 16_000) }.apply { isDaemon = true; start() }
    t.join(ms)
    if (t.isAlive) throw AssertionError("Wav.decodeMono16 did not return within $ms ms")
    return out
  }

  @Test fun `a well-formed file decodes`() {
    val b = riff().fmt().s("data").i32(3200).bytes(ByteArray(3200)).build()
    val d = requireNotNull(decodeWithin(b))
    assertEquals(1600, d.pcm.size)
    assertEquals(16_000, d.sourceRate)
  }

  @Test fun `a chunk size that wraps negative ends the walk instead of looping`() {
    // 0xFFFFFFF8 read as a signed Int is -8: `i += 8 + size` stepped back onto the same chunk forever.
    val b = riff().fmt().s("LIST").i32(0xFFFFFFF8L).bytes(ByteArray(64)).build()
    assertNull(decodeWithin(b))
  }

  @Test fun `chunk sizes larger than the file are rejected`() {
    assertNull(decodeWithin(riff().fmt().s("junk").i32(0x7FFFFFF0L).bytes(ByteArray(64)).build()))
    assertNull(decodeWithin(riff().s("fmt ").i32(0xFFFFFFFFL).bytes(ByteArray(40)).build()))
    assertNull(decodeWithin(riff().s("fmt ").i32(8).bytes(ByteArray(40)).build())) // fmt too short to hold its fields
  }

  @Test fun `an oversize data length plays only what is present`() {
    // Streaming writers leave 0xFFFFFFFF in the data size.
    val b = riff().fmt().s("data").i32(0xFFFFFFFFL).bytes(ByteArray(800)).build()
    assertEquals(400, decodeWithin(b)!!.pcm.size)
  }

  @Test fun `sample rates outside 8 to 48 kHz are rejected`() {
    for (rate in listOf(0L, 1L, 7_999L, 48_001L, 0xFFFFFFFFL)) {
      assertNull("rate $rate", decodeWithin(riff().fmt(rate = rate).s("data").i32(800).bytes(ByteArray(800)).build()))
    }
    assertNotNull(decodeWithin(riff().fmt(rate = 8_000).s("data").i32(800).bytes(ByteArray(800)).build()))
    assertNotNull(decodeWithin(riff().fmt(rate = 48_000).s("data").i32(800).bytes(ByteArray(800)).build()))
  }

  @Test fun `audio longer than the cap is rejected`() {
    val frames = Wav.MAX_SECONDS * 8_000 + 1
    val b = riff().fmt(rate = 8_000).s("data").i32(frames * 2L).bytes(ByteArray(frames * 2)).build()
    assertNull(decodeWithin(b, 10_000))
  }

  @Test fun `zero channels, wrong bit depth, missing fmt and non-RIFF input are rejected`() {
    assertNull(decodeWithin(riff().fmt(channels = 0).s("data").i32(800).bytes(ByteArray(800)).build()))
    assertNull(decodeWithin(riff().fmt(bits = 8).s("data").i32(800).bytes(ByteArray(800)).build()))
    assertNull(decodeWithin(riff().s("data").i32(800).bytes(ByteArray(800)).build()))
    assertNull(decodeWithin(W().s("RIFX").i32(0).s("WAVE").fmt().s("data").i32(800).bytes(ByteArray(800)).build()))
  }

  @Test fun `many tiny chunks still terminate`() {
    val w = riff().fmt()
    repeat(5_000) { w.s("pad ").i32(0) }
    assertNull(decodeWithin(w.build()))
  }
}
