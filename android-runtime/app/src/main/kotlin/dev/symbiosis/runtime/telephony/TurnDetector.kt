package dev.symbiosis.runtime.telephony

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Semantic end-of-turn detection: "does this person sound finished?"
 *
 * An energy VAD only knows that the line went quiet. People pause mid-sentence for longer than they pause at
 * the end of one, so a fixed silence timer is always wrong in one direction: too short and it chops "I'd like a
 * table for... two" in half, too long and every reply carries that dead air. This is pipecat's smart-turn v3
 * (BSD-2, https://github.com/pipecat-ai/smart-turn): a Whisper-Tiny encoder with a linear head, 8M parameters,
 * trained on prosody and content to say whether the last few seconds of speech ended a turn. It runs on the
 * phone CPU in tens of milliseconds, once per pause, from the model in `assets/`.
 *
 * Input is the last 8 s of the far party's turn at 16 kHz (shorter turns are zero-padded at the *front* so the
 * speech ends where the window ends); output is P(turn complete). [CallConversation] uses it to end a turn
 * after a short pause when the model is confident, and to keep waiting — up to a ceiling — when it is not.
 */
class TurnDetector private constructor(private val env: OrtEnvironment, private val session: OrtSession) : AutoCloseable {

  /** P(complete) for the turn whose audio is [pcm] (16 kHz mono 16-bit); only the last [WINDOW_SAMPLES] count. */
  fun probability(pcm: ShortArray, len: Int = pcm.size): Float {
    val window = ShortArray(WINDOW_SAMPLES)
    val n = minOf(len, WINDOW_SAMPLES)
    System.arraycopy(pcm, len - n, window, WINDOW_SAMPLES - n, n)
    return probabilityFromFeatures(WhisperMel.features(window, constantBefore = WINDOW_SAMPLES - n))
  }

  /** Run the classifier on precomputed features (N_MELS x frames, row-major). */
  fun probabilityFromFeatures(features: FloatArray): Float {
    val frames = features.size / WhisperMel.N_MELS
    val shape = longArrayOf(1, WhisperMel.N_MELS.toLong(), frames.toLong())
    OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape).use { input ->
      session.run(mapOf(INPUT to input)).use { out ->
        val raw = ((out[0].value as Array<*>)[0] as FloatArray)[0]
        // Exported as "logits" but the values observed are already probabilities; be robust to either.
        return if (raw in 0f..1f) raw else (1.0 / (1.0 + exp(-raw.toDouble()))).toFloat()
      }
    }
  }

  override fun close() { runCatching { session.close() } }

  companion object {
    const val ASSET = "smart-turn-v3.2-cpu.onnx"
    const val WINDOW_SECONDS = 8
    const val WINDOW_SAMPLES = WINDOW_SECONDS * WhisperMel.SAMPLE_RATE
    private const val INPUT = "input_features"

    fun fromBytes(model: ByteArray): TurnDetector {
      val env = OrtEnvironment.getEnvironment()
      val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      }
      return TurnDetector(env, env.createSession(model, opts))
    }

    fun load(context: Context): Result<TurnDetector> = runCatching {
      fromBytes(context.assets.open(ASSET).use { it.readBytes() })
    }
  }
}
