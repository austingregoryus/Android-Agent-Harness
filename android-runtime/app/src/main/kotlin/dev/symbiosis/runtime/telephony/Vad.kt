package dev.symbiosis.runtime.telephony

import kotlin.math.sqrt

/**
 * Energy-based voice activity detection with hysteresis, used to endpoint the far party's speech on a call.
 *
 * Deliberately simple: a carrier downlink is narrowband, already noise-suppressed by the network, and we only
 * need "is someone talking right now" — not phoneme-accurate boundaries. Two thresholds (enter/exit) plus a
 * hangover stop it flapping on the pauses inside a sentence, and [startMs] stops a single click opening a turn.
 *
 * The caller feeds fixed-size frames and reads [state] transitions; nothing here allocates or blocks, so it can
 * run on the capture thread.
 */
class Vad(
  private val sampleRate: Int = 16_000,
  /** RMS above this (0..32767) starts speech. */
  private val enterRms: Double = 550.0,
  /** RMS below this ends it; lower than [enterRms] so a trailing-off word does not chop. */
  private val exitRms: Double = 300.0,
  /** Sustained loud audio required before a turn opens — rejects clicks and line pops. */
  private val startMs: Int = 120,
  /** Silence required before a turn closes — spans the natural gaps inside a sentence. */
  private val hangoverMs: Int = 700,
) {
  enum class State { SILENCE, SPEECH }

  var state: State = State.SILENCE
    private set

  private var loudMs = 0
  private var quietMs = 0

  /** Speech duration of the turn that just ended, in ms; valid immediately after [Event.END]. */
  var lastTurnMs: Int = 0
    private set
  private var turnMs = 0

  /** RMS of the most recent frame — diagnostic, reported on events so thresholds can be tuned from logs. */
  var lastRms: Double = 0.0
    private set

  enum class Event { NONE, START, END }

  /** Feed one frame; returns a transition if this frame opened or closed a turn. */
  fun accept(frame: ShortArray, len: Int = frame.size, gain: Double = 1.0): Event {
    if (len <= 0) return Event.NONE
    val ms = len * 1000 / sampleRate
    val r = rms(frame, len) * gain
    lastRms = r
    return when (state) {
      State.SILENCE -> {
        if (r >= enterRms) {
          loudMs += ms
          if (loudMs >= startMs) { state = State.SPEECH; quietMs = 0; turnMs = loudMs; loudMs = 0; Event.START } else Event.NONE
        } else { loudMs = 0; Event.NONE }
      }
      State.SPEECH -> {
        turnMs += ms
        if (r <= exitRms) {
          quietMs += ms
          if (quietMs >= hangoverMs) {
            state = State.SILENCE
            // The hangover is trailing silence, not speech — don't count it toward the turn.
            lastTurnMs = (turnMs - quietMs).coerceAtLeast(0)
            turnMs = 0; quietMs = 0; loudMs = 0
            Event.END
          } else Event.NONE
        } else { quietMs = 0; Event.NONE }
      }
    }
  }

  /** Force the turn closed (call teardown, barge-in handling). Returns END if a turn was open. */
  fun reset(): Event {
    val wasSpeech = state == State.SPEECH
    if (wasSpeech) lastTurnMs = (turnMs - quietMs).coerceAtLeast(0)
    state = State.SILENCE; loudMs = 0; quietMs = 0; turnMs = 0
    return if (wasSpeech) Event.END else Event.NONE
  }

  companion object {
    fun rms(b: ShortArray, len: Int = b.size): Double {
      if (len <= 0) return 0.0
      var sum = 0.0
      for (i in 0 until len) { val v = b[i].toDouble(); sum += v * v }
      return sqrt(sum / len)
    }
  }
}
