package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.sessions.SessionFrame
import dev.symbiosis.runtime.sessions.SessionFrameCursor
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** /ws/sessions ordering: backlog plus live without loss or duplicates, holes refilled, gaps when they cannot be. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionFrameCursorTest {
  private val sid = "call_test"

  /** An in-memory session_messages: frames 1..n persisted. */
  private class Store(val sid: String) {
    val frames = ArrayList<SessionFrame>()
    fun add(): SessionFrame = SessionFrame(sid, frames.size + 1L, 0, null, "transcript.final", buildJsonObject { put("text", "f${frames.size + 1}") }).also { frames += it }
    fun fetch(after: Long, limit: Int): Pair<List<JsonObject>, Boolean> {
      val rest = frames.filter { it.seq > after }
      return rest.take(limit).map { it.toJson() } to (rest.size > limit)
    }
    fun head() = frames.size.toLong()
  }

  private fun cursor(store: Store, since: Long, page: Int = 500) = SessionFrameCursor(sid, since, store::fetch, store::head, page = page, clock = { 0 })
  private fun seqs(l: List<JsonObject>) = l.map { it["seq"]!!.jsonPrimitive.long }
  private fun types(l: List<JsonObject>) = l.map { it["type"]!!.jsonPrimitive.content }

  @Test fun `frames emitted while the backlog is read are neither lost nor repeated`() {
    val store = Store(sid)
    repeat(3) { store.add() }
    val c = cursor(store, 0)
    // Subscribed first; frame 4 is published (and persisted) before the backlog read.
    val f4 = store.add()
    val out = c.backlog() + c.live(f4) + c.live(store.add())
    assertEquals(listOf(1L, 2, 3, 4, 5), seqs(out))
  }

  @Test fun `a slow socket's missed live frames are refilled from the store`() {
    val store = Store(sid)
    val c = cursor(store, 0)
    assertTrue(c.backlog().isEmpty())
    val first = store.add()
    assertEquals(listOf(1L), seqs(c.live(first)))
    repeat(5) { store.add() } // 2..6 dropped by the flow for this slow collector
    val seven = store.add()
    assertEquals(listOf(2L, 3, 4, 5, 6, 7), seqs(c.live(seven)))
    assertEquals(7L, c.last)
  }

  @Test fun `a hole larger than one page becomes a gap frame`() {
    val store = Store(sid)
    val c = cursor(store, 0, page = 3)
    c.backlog()
    repeat(10) { store.add() }
    val eleven = store.add()
    val out = c.live(eleven)
    assertEquals(listOf("transcript.final", "transcript.final", "transcript.final", "gap", "transcript.final"), types(out))
    val gap = out[3]
    assertEquals(4L, gap["from_seq"]!!.jsonPrimitive.long)
    assertEquals(10L, gap["to_seq"]!!.jsonPrimitive.long)
    assertEquals(3L, gap["seq"]!!.jsonPrimitive.long) // resume point: the last frame delivered before the hole
    assertEquals(11L, seqs(out).last())
  }

  @Test fun `a long backlog sends a page then a gap, then continues live`() {
    val store = Store(sid)
    repeat(7) { store.add() }
    val c = cursor(store, 0, page = 3)
    val out = c.backlog()
    assertEquals(listOf("transcript.final", "transcript.final", "transcript.final", "gap"), types(out))
    assertEquals(4L, out[3]["from_seq"]!!.jsonPrimitive.long)
    assertEquals(7L, out[3]["to_seq"]!!.jsonPrimitive.long)
    assertTrue(c.live(store.frames[5]).isEmpty()) // inside the announced gap: not sent twice
    assertEquals(listOf(8L), seqs(c.live(store.add())))
  }

  @Test fun `since_seq resumes after the given frame and a future since_seq is clamped to the head`() {
    val store = Store(sid)
    repeat(5) { store.add() }
    assertEquals(listOf(4L, 5), seqs(cursor(store, 3).backlog()))
    val c = cursor(store, 99)
    assertTrue(c.backlog().isEmpty())
    assertEquals(listOf(6L), seqs(c.live(store.add())))
  }

  @Test fun `other sessions' frames are ignored`() {
    val store = Store(sid)
    val c = cursor(store, 0)
    assertTrue(c.live(SessionFrame("call_other", 1, 0, null, "x", JsonObject(emptyMap()))).isEmpty())
  }

  @Test fun `a collector that never reads does not block emission or other collectors`() = runBlocking {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    ctx.deleteDatabase("symbiosis.db")
    val sessions = SessionManager(Db(ctx))
    val s = sessions.create("call", "peer_a", "corr_a", emptyMap())
    val stuckReady = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    // A socket that has stopped reading: suspended inside collect on its first frame.
    val stuck = launch(Dispatchers.Default) { sessions.frames.onSubscription { stuckReady.complete(Unit) }.collect { release.await() } }
    stuckReady.await()
    val seen = ArrayList<Long>()
    val ready = CompletableDeferred<Unit>()
    val healthy = launch(Dispatchers.Default) {
      val c = SessionFrameCursor(s.sessionId, 0, { a, l -> sessions.framesAfter(s.sessionId, a, l) }, { sessions.headSeq(s.sessionId) })
      sessions.frames.onSubscription { synchronized(seen) { seen += c.backlog().map { it["seq"]!!.jsonPrimitive.long } }; ready.complete(Unit) }
        .collect { f -> synchronized(seen) { seen += c.live(f).map { it["seq"]!!.jsonPrimitive.long } } }
    }
    ready.await()
    val total = 2_000 // far beyond the 512-frame buffer the stuck collector pins
    withTimeout(20_000) { repeat(total) { sessions.emitFrame(s.sessionId, null, "transcript.partial", buildJsonObject { put("i", it) }) } }
    val head = sessions.headSeq(s.sessionId)
    withTimeout(20_000) { while (synchronized(seen) { seen.lastOrNull() } != head) kotlinx.coroutines.delay(20) }
    assertEquals((1L..head).toList(), synchronized(seen) { seen.toList() })
    release.complete(Unit); stuck.cancel(); healthy.cancel()
  }
}
