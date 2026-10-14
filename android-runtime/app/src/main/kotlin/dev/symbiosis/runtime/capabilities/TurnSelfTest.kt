package dev.symbiosis.runtime.capabilities

import android.content.Context
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.telephony.TelephonyAudio
import dev.symbiosis.runtime.telephony.Tts
import dev.symbiosis.runtime.telephony.TurnDetector
import dev.symbiosis.runtime.telephony.WhisperMel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Run the end-of-turn model on this phone with no call involved and report what it says and how long it took.
 *
 * The classifier is compared against the reference pipeline on supported JVM hosts; what that cannot tell us is whether
 * the ONNX runtime loads on this device, and what the 8 s window really costs on this CPU — which is what
 * decides whether it can sit on the capture thread. Debug builds only.
 */
object TurnSelfTest {

  private const val GOLDEN = "smartturn_golden.pcm"

  fun capability(context: Context): Capability = Capability(
    name = "debug.turn_selftest",
    description = "RESEARCH (debug). Run the end-of-turn model (smart-turn v3) on a generated non-speech numerical fixture and optionally on a synthesized phrase; reports measured and expected probabilities and on-device latency. The fixture does not measure conversation accuracy.",
    params = listOf(
      Param("text", "string", "Optional phrase to synthesize and classify as well"),
      Param("runs", "integer", "Timing repetitions (default 3)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, _ ->
    val text = args["text"]?.toString()?.takeIf { it.isNotBlank() }
    val runs = ((args["runs"] as? Number)?.toInt() ?: 3).coerceIn(1, 10)

    withContext(Dispatchers.IO) {
      val t0 = System.currentTimeMillis()
      val det = TurnDetector.load(context).getOrElse {
        return@withContext CapabilityResult.fail("capability.failed", "model load failed: ${it.message}")
      }
      val loadMs = System.currentTimeMillis() - t0
      try {
        val bytes = context.assets.open(GOLDEN).use { it.readBytes() }
        val metadata = org.json.JSONObject(context.assets.open("smartturn_golden.json").bufferedReader().use { it.readText() })
        val golden = ShortArray(bytes.size / 2) { i ->
          ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        var pGolden = 0f
        var featMs = 0L
        var inferMs = 0L
        repeat(runs) {
          val a = System.nanoTime()
          val f = WhisperMel.features(golden)
          val b = System.nanoTime()
          pGolden = det.probabilityFromFeatures(f)
          val c = System.nanoTime()
          featMs += (b - a) / 1_000_000; inferMs += (c - b) / 1_000_000
        }
        val out = HashMap<String, Any?>()
        out["load_ms"] = loadMs
        out["golden_p_complete"] = pGolden
        out["golden_expected"] = metadata.getDouble("expected_probability")
        out["golden_fixture"] = metadata.getString("fixture")
        out["features_ms"] = featMs / runs
        out["inference_ms"] = inferMs / runs
        out["total_ms"] = (featMs + inferMs) / runs
        if (text != null) {
          val speech = Tts.synthesize(context, text)
          if (speech != null) {
            val a = System.nanoTime()
            val p = det.probability(speech.pcm)
            out["text"] = text
            out["text_p_complete"] = p
            out["text_ms"] = (System.nanoTime() - a) / 1_000_000
            out["text_audio_ms"] = speech.pcm.size * 1000L / TelephonyAudio.RATE
          }
        }
        CapabilityResult.ok(out)
      } finally {
        det.close()
      }
    }
  }
}
