package dev.symbiosis.runtime.telephony

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The Kotlin Whisper front end must reproduce `transformers.WhisperFeatureExtractor(chunk_length=8)` closely
 * enough that the int8 model gives the same answer. The 8 s, 16 kHz fixture is entirely generated non-speech:
 * silence followed by a chirp, a tone and seeded noise. Reference features and probability are independently
 * produced by tools/generate_turn_fixture.py. This checks numerical compatibility, not spoken-turn accuracy.
 */
class TurnDetectorTest {

  private fun resource(name: String): ByteArray =
    javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

  private fun goldenPcm(): ShortArray {
    val b = ByteBuffer.wrap(resource("smartturn_golden.pcm")).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(b.remaining() / 2) { b.getShort() }
  }

  private fun goldenFeatures(): FloatArray {
    val b = ByteBuffer.wrap(resource("smartturn_golden.f32")).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(b.remaining() / 4) { b.getFloat() }
  }

  private fun goldenMetadata() = Json.parseToJsonElement(resource("smartturn_golden.json").decodeToString()).jsonObject

  @Test
  fun `fixture manifest matches the model and both packaged copies`() {
    val meta = goldenMetadata()
    fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
      .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    assertEquals(meta["pcm_sha256"]!!.jsonPrimitive.content, digest(resource("smartturn_golden.pcm")))
    assertEquals(meta["features_sha256"]!!.jsonPrimitive.content, digest(resource("smartturn_golden.f32")))
    assertEquals(meta["model_sha256"]!!.jsonPrimitive.content, digest(File("src/main/assets/${TurnDetector.ASSET}").readBytes()))
    assertTrue(resource("smartturn_golden.pcm").contentEquals(File("src/main/assets/smartturn_golden.pcm").readBytes()))
    assertTrue(resource("smartturn_golden.json").contentEquals(File("src/main/assets/smartturn_golden.json").readBytes()))
  }

  @Test
  fun `the 400-point FFT equals a direct DFT`() {
    val rnd = java.util.Random(7)
    val re = DoubleArray(400) { rnd.nextGaussian() }
    val im = DoubleArray(400)
    val want = Array(400) { k ->
      var sr = 0.0; var si = 0.0
      for (n in 0 until 400) {
        val a = -2.0 * Math.PI * k * n / 400
        sr += re[n] * Math.cos(a); si += re[n] * Math.sin(a)
      }
      doubleArrayOf(sr, si)
    }
    WhisperMel.Fft400().transform(re, im)
    for (k in 0 until 400) {
      assertEquals("re[$k]", want[k][0], re[k], 1e-9)
      assertEquals("im[$k]", want[k][1], im[k], 1e-9)
    }
  }

  @Test
  fun `constant-prefix shortcut gives the same features`() {
    val pcm = goldenPcm()
    // The generated signal follows five seconds of zeros; find the first non-zero sample.
    var first = 0
    while (first < pcm.size && pcm[first].toInt() == 0) first++
    val full = WhisperMel.features(pcm)
    val t0 = System.nanoTime()
    val fast = WhisperMel.features(pcm, constantBefore = first)
    println("features with shortcut (${first} constant samples): ${(System.nanoTime() - t0) / 1_000_000} ms")
    var maxErr = 0f
    for (i in full.indices) maxErr = maxOf(maxErr, abs(full[i] - fast[i]))
    assertTrue("shortcut changed features by $maxErr", maxErr < 1e-6f)
  }

  @Test
  fun `features match the reference extractor`() {
    val pcm = goldenPcm()
    assertEquals(TurnDetector.WINDOW_SAMPLES, pcm.size)
    val want = goldenFeatures()
    val t0 = System.nanoTime()
    val got = WhisperMel.features(pcm)
    val ms = (System.nanoTime() - t0) / 1e6
    assertEquals(want.size, got.size)
    var maxErr = 0f
    var sumErr = 0.0
    var over = 0
    for (i in want.indices) {
      val e = abs(want[i] - got[i]); if (e > maxErr) maxErr = e; sumErr += e; if (e > 0.01f) over++
    }
    println("features: ${ms.toInt()} ms, max abs err $maxErr, mean ${sumErr / want.size}, >0.01: $over of ${want.size}")
    // The reference computes its STFT in float32; near-silent bins sit on that noise floor and ours (float64)
    // come out a little lower there. Everything with signal in it matches to ~1e-4. The classifier's verdict
    // on our features is checked separately below.
    assertTrue("mean abs error ${sumErr / want.size}", sumErr / want.size < 1e-3)
    assertTrue("$over bins differ by >0.01", over < want.size / 200)
  }

  /**
   * The JVM ONNX runtime's native library does not initialise on every host (on this Windows workstation the
   * DLL fails in its init routine for 1.28 and 1.29). On-device verification can use
   * `debug.turn_selftest`; here we only skip rather than fail when the host runtime is unusable.
   */
  private fun detectorOrSkip(optimization: OptLevel = OptLevel.ALL_OPT): TurnDetector {
    val model = File("src/main/assets/${TurnDetector.ASSET}").readBytes()
    return try {
      TurnDetector.fromBytes(model, optimization)
    } catch (e: Throwable) {
      if (e is UnsatisfiedLinkError || e is NoClassDefFoundError || e.cause is UnsatisfiedLinkError) {
        assumeTrue("ONNX runtime native library unavailable on this host: ${e.message}", false)
      }
      throw e
    }
  }

  @Test
  fun `model reproduces the reference probability`() {
    // Against the stored reference: unoptimized float execution, identical on every CPU. The fused int8 kernels
    // that ALL_OPT selects vary with the host CPU (a GitHub runner produced more than twice the reference).
    detectorOrSkip(OptLevel.NO_OPT).use { exact ->
      val want = goldenMetadata()["expected_probability"]!!.jsonPrimitive.float
      val got = exact.probabilityFromFeatures(goldenFeatures())
      assertEquals("unoptimized model on reference features: got $got, reference $want", want, got, 0.01f)
    }
    // Our front end against the reference features, both through the production session on this host.
    detectorOrSkip().use { det ->
      val pRef = det.probabilityFromFeatures(goldenFeatures())
      val t0 = System.nanoTime()
      val p = det.probability(goldenPcm())
      println("end-to-end: ${(System.nanoTime() - t0) / 1_000_000} ms, p=$p (reference features -> $pRef)")
      assertEquals("end-to-end p=$p vs reference features $pRef", pRef, p, 0.02f)
    }
  }

  @Test
  fun `short input is padded at the front and only len samples count`() {
    // Smart Turn v3.2 scores pure digital silence as a finished turn (p~0.99, same under the Python
    // onnxruntime), so silence is no test of padding. CallConversation only asks after the VAD has heard speech.
    detectorOrSkip().use { det ->
      val pcm = goldenPcm()
      val tail = pcm.copyOfRange(pcm.size - 48_000, pcm.size) // the last 3 s carry the generated signal
      val padded = ShortArray(TurnDetector.WINDOW_SAMPLES).also { tail.copyInto(it, TurnDetector.WINDOW_SAMPLES - tail.size) }
      val want = det.probability(padded)
      assertEquals("short input must equal an explicitly front-padded window", want, det.probability(tail), 1e-4f)
      val withTrailing = tail + ShortArray(8_000) { 12_000 }
      assertEquals("samples after len must be ignored", want, det.probability(withTrailing, tail.size), 1e-4f)
    }
  }
}
