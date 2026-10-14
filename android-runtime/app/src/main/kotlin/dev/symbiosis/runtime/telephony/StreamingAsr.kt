package dev.symbiosis.runtime.telephony

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming speech recognition over PCM we supply ourselves.
 *
 * The stock [SpeechRecognizer] listens to the microphone, which is useless here: during a call we need the far
 * party's voice, which we capture from `VOICE_DOWNLINK`. Android 13 added `EXTRA_AUDIO_SOURCE`, which lets a
 * caller hand the recognizer a pipe instead — so we feed it the downlink and it never touches the mic.
 *
 * Recognition runs *while* the person is still talking and emits partial results, so when the VAD closes the
 * turn the transcript is usually already complete. Waiting for end-of-speech and only then starting ASR would
 * add its full latency to every turn.
 *
 * Each turn is its own [Session] (pipe + recognizer + callbacks). Turns overlap: the final result of turn N
 * arrives 50-300 ms after its pipe was closed, and the far party can resume inside that window. Two things
 * used to go wrong there. With one set of fields shared across turns, a `start()` inside the window silently
 * did nothing — turn N+1 had no pipe, every frame was dropped, no transcript was ever emitted. And the
 * recognizer service itself takes ONE client at a time: a second `startListening` while the first is still
 * finishing fails at once (`ERROR_RECOGNIZER_BUSY`, measured on the Pixel). So a new turn that arrives while
 * the previous one is still waiting for its final **buffers its audio in memory** and attaches the recognizer
 * the moment that final lands (or, after [PREV_FINAL_WAIT_MS], closes the previous turn with its last partial
 * and goes ahead). Nothing is dropped, and the recognizer catches up on the buffered audio faster than real
 * time.
 *
 * [SpeechRecognizer] is main-thread-only, so every call into it is posted to the main looper while the capture
 * thread writes PCM into the pipe.
 */
class StreamingAsr(private val context: Context) {

  private val main = Handler(Looper.getMainLooper())
  private val current = AtomicReference<Session?>(null)

  @Volatile var lastPartial: String = ""
    private set

  val isRunning: Boolean get() = current.get()?.open?.get() == true

  /** True when this device can recognise without a network round-trip (much lower latency). */
  fun onDeviceAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  /** One turn's recognition: its own pipe, recognizer and callbacks, independent of the next turn's. */
  private inner class Session(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String?) -> Unit,
  ) {
    /** The turn is still being fed (audio may still arrive). */
    val open = AtomicBoolean(true)
    val done = AtomicBoolean(false)
    private val lock = Any()
    // Until the recognizer reports ready, audio accumulates here (a resumed turn waiting for the previous
    // turn's final, or a recognizer that has not come up yet). Bounded: beyond this the previous turn is
    // forced closed instead.
    private var buffer: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var endedBeforeAttach = false
    private var attempts = 0
    @Volatile private var sink: OutputStream? = null
    @Volatile private var pendingOut: OutputStream? = null
    @Volatile private var readSide: ParcelFileDescriptor? = null
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var live = false
    @Volatile var partial: String = ""
      private set
    /** Runs (on main) once this session has delivered its final: the next turn's attach. */
    @Volatile var onDone: (() -> Unit)? = null

    /**
     * Open the pipe and start the recognizer. Main thread. Buffered audio is NOT handed over yet: the
     * service may still be releasing the previous turn (a `startListening` right after its final failed at
     * once on the Pixel), so the pipe only goes live on `onReadyForSpeech`, and an error before that point
     * retries the attach with the buffer intact.
     */
    fun attach() {
      if (done.get()) return
      attempts++
      val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
      if (pipe == null) { android.util.Log.w(TAG, "no pipe"); finish(null); return }
      val rec = runCatching {
        if (onDeviceAvailable()) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
      }.getOrNull()
      if (rec == null) { android.util.Log.w(TAG, "no recognizer"); runCatching { pipe[0].close(); pipe[1].close() }; finish(null); return }
      readSide = pipe[0]
      pendingOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
      recognizer = rec
      rec.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { android.util.Log.i(TAG, "ready (ended=$endedBeforeAttach)"); goLive(rec) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { android.util.Log.i(TAG, "endOfSpeech (live=$live)") }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
          goLive(rec)
          val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (!t.isNullOrBlank() && !done.get()) {
            partial = t
            if (current.get() === this@Session) lastPartial = t
            onPartial(t)
          }
        }
        override fun onResults(results: Bundle?) {
          val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (t.isNullOrBlank() && !live && partial.isBlank() && !done.get() && attempts < MAX_ATTACH_ATTEMPTS) {
            // An empty result before the recognizer ever went live (seen ~60 ms after startListening) is
            // the service refusing us while it tears down the previous client, not a recognition.
            android.util.Log.w(TAG, "empty results before ready (attempt $attempts); retrying")
            dropRecognizer(rec)
            main.postDelayed({ attach() }, ATTACH_RETRY_MS)
            return
          }
          if (t.isNullOrBlank()) android.util.Log.w(TAG, "results empty (live=$live, partial='$partial')")
          finish(t?.takeIf { it.isNotBlank() } ?: partial.takeIf { it.isNotBlank() })
        }
        override fun onError(error: Int) {
          if (!live && !done.get() && attempts < MAX_ATTACH_ATTEMPTS) {
            // Failed before it ever took audio (busy with the previous turn, most likely): try again shortly.
            android.util.Log.w(TAG, "recognizer error $error before ready (attempt $attempts); retrying")
            dropRecognizer(rec)
            main.postDelayed({ attach() }, ATTACH_RETRY_MS)
            return
          }
          // A no-match/timeout after usable partials is still a usable turn.
          if (partial.isBlank()) android.util.Log.w(TAG, "recognizer error $error with no partial (live=$live)")
          finish(partial.takeIf { it.isNotBlank() })
        }
      })
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TelephonyAudio.RATE)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }
      if (runCatching { rec.startListening(intent) }.onFailure { android.util.Log.w(TAG, "startListening failed: $it") }.isFailure) {
        dropRecognizer(rec)
        if (attempts < MAX_ATTACH_ATTEMPTS) main.postDelayed({ attach() }, ATTACH_RETRY_MS) else finish(null)
        return
      }
      // Not every recognizer reports ready for a piped source; do not hold the audio hostage to it.
      main.postDelayed({ if (recognizer === rec) goLive(rec) }, READY_FALLBACK_MS)
    }

    /**
     * Hand over: whatever was buffered goes first, then live frames write straight to the pipe.
     *
     * The write happens off the main thread (a long buffered turn can exceed the pipe's 64 KB and block
     * until the recognizer drains it). A turn that had already ENDED before the recognizer came up gets a
     * tail of silence and a delayed EOF: handed the whole turn and an immediate end-of-stream, the
     * recognizer returned nothing at all (measured: a one-word turn fed faster than real time came back
     * null every time, the same word fed live came back fine) — and that is what lost two short turns on a
     * live call.
     */
    private fun goLive(rec: SpeechRecognizer) {
      if (recognizer !== rec || done.get()) return
      val out: OutputStream
      val buffered: ByteArray?
      val ended: Boolean
      synchronized(lock) {
        if (live) return
        live = true
        out = pendingOut ?: return
        pendingOut = null
        buffered = buffer?.toByteArray()
        buffer = null
        sink = out
        ended = endedBeforeAttach
      }
      if (buffered == null || buffered.isEmpty()) {
        if (ended) closeSinkAfterTail()
        return
      }
      Thread({
        runCatching { out.write(buffered); out.flush() }
        if (ended) closeSinkAfterTail()
      }, "symbiosis-asr-handover").apply { isDaemon = true }.start()
    }

    /** The turn is over but the recognizer only just started reading: give it a tail before the EOF. */
    private fun closeSinkAfterTail() {
      val out = sink ?: return
      runCatching { out.write(ByteArray(TAIL_SILENCE_BYTES)); out.flush() }
      main.postDelayed({ closeSink() }, TAIL_BEFORE_EOF_MS)
    }

    /** Throw away a recognizer that never went live (and its pipe); the buffer stays. */
    private fun dropRecognizer(rec: SpeechRecognizer) {
      if (recognizer === rec) recognizer = null
      runCatching { pendingOut?.close() }; pendingOut = null
      closeRead()
      runCatching { rec.destroy() }
    }

    fun feed(pcm: ShortArray, len: Int) {
      if (!open.get()) return
      val bytes = ByteArray(len * 2)
      for (i in 0 until len) {
        val v = pcm[i].toInt()
        bytes[i * 2] = (v and 0xff).toByte()
        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
      }
      synchronized(lock) {
        val b = buffer
        if (b != null) {
          if (b.size() < MAX_BUFFER_BYTES) b.write(bytes)
          return
        }
      }
      // A closed pipe just means the recognizer finished first; that is not an error worth surfacing.
      val out = sink ?: return
      runCatching { out.write(bytes); out.flush() }
    }

    /** End of the turn's audio: close the pipe so the recognizer sees end-of-stream and emits its final. */
    fun end() {
      if (!open.compareAndSet(true, false)) return
      val isLive: Boolean
      synchronized(lock) {
        isLive = live
        if (!isLive) endedBeforeAttach = true
      }
      if (isLive) closeSink()
    }

    private fun closeSink() {
      // Closing the pipe is the end-of-stream signal; the recognizer finalises on it. stopListening is only a
      // safety net, and it must not land the instant the recognizer came up: on a short turn that ended before
      // the ready callback, an immediate stopListening made it give up before it had read the pipe at all —
      // two one-word turns on a live call produced no transcript.
      runCatching { sink?.close() }
      sink = null
      main.postDelayed({ runCatching { recognizer?.stopListening() } }, STOP_LISTENING_DELAY_MS)
    }

    /** Abandon: no final result is delivered. */
    fun cancel() {
      end()
      if (!done.compareAndSet(false, true)) return
      closeRead()
      main.post { runCatching { recognizer?.cancel() }; destroy(); onDone?.invoke() }
    }

    /**
     * The next turn cannot wait any longer for this one's final: deliver the last partial as the final (the
     * recognizer's own no-match path does the same) and free the service.
     */
    fun forceFinishWithPartial() {
      if (done.get()) return
      main.post {
        if (done.get()) return@post
        runCatching { recognizer?.cancel() }
        finish(partial.takeIf { it.isNotBlank() })
      }
    }

    /** The recognizer is done with this turn (result, error, or failure to start). Delivers exactly once. */
    private fun finish(text: String?) {
      if (!done.compareAndSet(false, true)) return
      open.set(false)
      synchronized(lock) { buffer = null }
      runCatching { sink?.close() }; sink = null
      runCatching { pendingOut?.close() }; pendingOut = null
      closeRead()
      destroy()
      onFinal(text)
      onDone?.invoke()
    }

    private fun closeRead() { runCatching { readSide?.close() }; readSide = null }

    private fun destroy() {
      val r = recognizer ?: return
      recognizer = null
      if (Looper.myLooper() === Looper.getMainLooper()) runCatching { r.destroy() }
      else main.post { runCatching { r.destroy() } }
    }
  }

  /**
   * Begin a turn. [onPartial] fires as words firm up; [onFinal] fires once with the completed transcript
   * (or null if nothing was recognised). A previous turn still waiting for its final keeps waiting; this
   * turn's audio is buffered until then and nothing is lost.
   */
  fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { onFinal(null); return }
    lastPartial = ""
    val s = Session(onPartial, onFinal)
    val prev = current.getAndSet(s)
    prev?.end() // should already be ended by finish(); harmless if so
    main.post {
      if (prev != null && !prev.done.get()) {
        // The recognizer service is still busy with the previous turn. Attach when it is free.
        prev.onDone = { if (!s.done.get()) s.attach() }
        main.postDelayed({ if (!prev.done.get()) prev.forceFinishWithPartial() }, PREV_FINAL_WAIT_MS)
        if (prev.done.get()) { prev.onDone = null; s.attach() } // it finished while we were posting
      } else {
        s.attach()
      }
    }
  }

  /** Push captured downlink PCM at the recognizer. Safe to call from the capture thread. */
  fun feed(pcm: ShortArray, len: Int) { current.get()?.feed(pcm, len) }

  /**
   * End of turn: close the pipe so the recognizer sees end-of-stream and emits its final result.
   * [onFinal] fires from the listener, not from here.
   */
  fun finish() { current.get()?.end() }

  /** Abandon the turn (barge-in, call teardown); no final result is delivered. */
  fun cancel() { current.getAndSet(null)?.cancel() }

  companion object {
    private const val TAG = "StreamingAsr"
    /** How long a new turn waits for the previous turn's final before forcing it closed with its partial. */
    const val PREV_FINAL_WAIT_MS = 1000L
    /** A recognizer that errors before it ever reported ready is re-created this many times… */
    private const val MAX_ATTACH_ATTEMPTS = 4
    /** …this long apart. */
    private const val ATTACH_RETRY_MS = 120L
    /** If no ready callback comes, the buffered audio is handed over anyway after this long. */
    private const val READY_FALLBACK_MS = 400L
    /** stopListening trails the pipe's EOF by this much, so a short turn is read before it is stopped. */
    private const val STOP_LISTENING_DELAY_MS = 400L
    /** Silence appended to a turn that ended before the recognizer was live (500 ms at 16 kHz 16-bit)… */
    private const val TAIL_SILENCE_BYTES = TelephonyAudio.RATE
    /** …and how long after that the pipe is closed. */
    private const val TAIL_BEFORE_EOF_MS = 300L
    /** Audio a not-yet-attached turn may hold: 8 s at 16 kHz 16-bit. */
    private const val MAX_BUFFER_BYTES = 8 * TelephonyAudio.RATE * 2

    /**
     * Load the recognition model before the call needs it. Measured cold start on a Pixel 9 Pro XL is ~9 s to
     * the first partial versus ~0.6 s once warm — and cold would otherwise land on the very first turn of the
     * call, which is the worst possible place for it. Feeding a short burst of silence is enough to force the
     * model load. Blocks; call it off the main thread.
     */
    fun warmUp(context: Context, timeoutMs: Long = 15_000) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
      val asr = StreamingAsr(context)
      val done = java.util.concurrent.CountDownLatch(1)
      asr.start(onPartial = {}, onFinal = { done.countDown() })
      val silence = ShortArray(TelephonyAudio.RATE / 10) // 100 ms
      repeat(3) { asr.feed(silence, silence.size) }
      asr.finish()
      if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) asr.cancel()
    }
  }
}
