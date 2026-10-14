package dev.symbiosis.runtime.policy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.symbiosis.runtime.R
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A pending approval row. */
data class PendingAction(
  val id: String,
  val createdAt: Long,
  val expiresAt: Long,
  val peerId: String?,
  val correlationId: String?,
  val capability: String,
  val actionClass: ActionClass,
  val args: Map<String, Any?>,
  val status: String, // pending | approved | denied | expired | cancelled | executed
)

/** Outcome of [ApprovalManager.resolve]. */
data class Resolution(val action: PendingAction, val approved: Boolean, val late: Boolean)

/**
 * Human-in-the-loop approvals. Creates a pending_actions row plus a high-priority notification with
 * Approve/Deny actions, and lets a tool call wait (bounded) for the decision. The waiter is registered
 * *before* the row and notification exist, so a fast tap can never race the caller into the late path.
 * Resolution is a conditional UPDATE, so two taps cannot both win.
 */
class ApprovalManager(private val context: Context, private val db: Db) {
  private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val requestCodes = AtomicInteger(1000)

  init { ensureChannel() }

  /** True when approval notifications can actually be shown (API 33+ needs the runtime permission). */
  fun notificationsAvailable(): Boolean =
    Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  /**
   * Create the pending action and wait up to [waitMs] for a decision.
   * Returns (action, true=approved / false=denied / null=still pending).
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  suspend fun createAndAwait(cap: String, actionClass: ActionClass, args: Map<String, Any?>, peerId: String?, correlationId: String?, ttlMs: Long, reason: String, waitMs: Long, allowLate: Boolean = true, onCreated: (PendingAction) -> Unit = {}): Pair<PendingAction, Boolean?> {
    val id = Ids.pendingAction()
    val deferred = CompletableDeferred<Boolean>()
    waiters[id] = deferred
    val pa = try { create(id, cap, actionClass, args, peerId, correlationId, ttlMs, reason) } catch (t: Throwable) { waiters.remove(id); throw t }
    runCatching { onCreated(pa) }
    try {
      val decision = withTimeoutOrNull(waitMs.coerceAtLeast(0)) { deferred.await() }
      return synchronized(waiters) {
        waiters.remove(id, deferred)
        // A tap can win just as await times out. Resolve and timeout share this lock, so either
        // the caller receives that decision or the pending action is cancelled before a late tap.
        val resolved = decision ?: if (deferred.isCompleted && !deferred.isCancelled) deferred.getCompleted() else null
        if (!allowLate && resolved == null) cancelOne(id)
        pa to resolved
      }
    } finally {
      synchronized(waiters) {
        waiters.remove(id, deferred)
        if (!allowLate) cancelOne(id) // also covers cancellation/disconnection while awaiting approval
      }
    }
  }

  private fun cancelOne(id: String) {
    db.writableDatabase.update("pending_actions", ContentValues().apply {
      put("status", "cancelled"); put("resolved_at", Ids.now())
    }, "pending_action_id = ? AND status = 'pending'", arrayOf(id))
    cancelNotification(id)
  }

  private fun create(id: String, cap: String, actionClass: ActionClass, args: Map<String, Any?>, peerId: String?, correlationId: String?, ttlMs: Long, reason: String): PendingAction {
    val now = Ids.now()
    val pa = PendingAction(id, now, now + ttlMs, peerId, correlationId, cap, actionClass, args, "pending")
    db.writableDatabase.insertOrThrow(
      "pending_actions", null,
      ContentValues().apply {
        put("pending_action_id", pa.id); put("created_at", pa.createdAt); put("expires_at", pa.expiresAt)
        put("peer_id", peerId); put("correlation_id", correlationId); put("capability", cap)
        put("action_class", actionClass.name)
        put("args_json", Json.lenient.encodeToString(JsonElement.serializer(), Json.toJson(args)))
        put("status", "pending")
      },
    )
    notify(pa, reason)
    return pa
  }

  /**
   * Resolve from the notification action / debug broadcast / UI. Exactly one caller wins the pending→decided
   * transition. Returns null when the id is unknown or already resolved/expired.
   */
  fun resolve(id: String, approved: Boolean): Resolution? {
    synchronized(waiters) {
    // The no-late rule survives process death: pending args are persisted, waiters are not.
    val pending = get(id)
    if (pending?.capability == "telephony.place_call" && pending.args["driver_managed"] == true && !waiters.containsKey(id)) {
      cancelOne(id)
      return null
    }
    val status = if (approved) "approved" else "denied"
    val now = Ids.now()
    val changed = db.writableDatabase.compileStatement(
      "UPDATE pending_actions SET status = ?, resolved_at = ? WHERE pending_action_id = ? AND status = 'pending' AND expires_at >= ?",
    ).use { it.bindString(1, status); it.bindLong(2, now); it.bindString(3, id); it.bindLong(4, now); it.executeUpdateDelete() }
    if (changed != 1) { expireStale(); cancelNotification(id); return null }
    cancelNotification(id)
    val pa = get(id) ?: return null
    val waiter = waiters.remove(id)
    if (waiter != null) { waiter.complete(approved); return Resolution(pa, approved, late = false) }
    return Resolution(pa, approved, late = true)
    }
  }

  fun markExecuted(id: String, result: Map<String, Any?>) {
    db.writableDatabase.update(
      "pending_actions",
      ContentValues().apply { put("status", "executed"); put("resolved_at", Ids.now()); put("result_json", Json.lenient.encodeToString(JsonElement.serializer(), Json.toJson(result))) },
      "pending_action_id = ?", arrayOf(id),
    )
  }

  /** Cancel every pending action (emergency stop) or those of one peer (revocation). Waiters see "denied". */
  fun cancelPending(peerId: String? = null, reason: String): Int {
    val ids = db.readableDatabase.query(
      "pending_actions", arrayOf("pending_action_id"), if (peerId == null) "status = 'pending'" else "status = 'pending' AND peer_id = ?",
      if (peerId == null) null else arrayOf(peerId), null, null, null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    for (id in ids) {
      db.writableDatabase.update("pending_actions", ContentValues().apply { put("status", "cancelled"); put("resolved_at", Ids.now()); put("result_json", "{\"reason\":\"$reason\"}") }, "pending_action_id = ? AND status = 'pending'", arrayOf(id))
      cancelNotification(id)
      waiters.remove(id)?.complete(false)
    }
    return ids.size
  }

  fun get(id: String): PendingAction? = db.readableDatabase.query("pending_actions", null, "pending_action_id = ?", arrayOf(id), null, null, null).use { c ->
    if (c.moveToFirst()) row(c) else null
  }

  fun pending(): List<PendingAction> {
    expireStale()
    return db.readableDatabase.query("pending_actions", null, "status = ?", arrayOf("pending"), null, null, "created_at DESC").use { c ->
      buildList { while (c.moveToNext()) add(row(c)) }
    }
  }

  /** Retention: resolved/expired rows older than [olderThanMs] are deleted. */
  fun prune(olderThanMs: Long = 7L * 24 * 3600 * 1000) {
    expireStale()
    db.writableDatabase.delete("pending_actions", "status != 'pending' AND created_at < ?", arrayOf((Ids.now() - olderThanMs).toString()))
  }

  private fun expireStale() {
    db.writableDatabase.update(
      "pending_actions", ContentValues().apply { put("status", "expired") },
      "status = 'pending' AND expires_at < ?", arrayOf(Ids.now().toString()),
    )
  }

  private fun row(c: android.database.Cursor) = PendingAction(
    id = c.getString(c.getColumnIndexOrThrow("pending_action_id")),
    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
    peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
    correlationId = c.getString(c.getColumnIndexOrThrow("correlation_id")),
    capability = c.getString(c.getColumnIndexOrThrow("capability")),
    actionClass = ActionClass.valueOf(c.getString(c.getColumnIndexOrThrow("action_class"))),
    args = Json.toMap(runCatching { Json.lenient.parseToJsonElement(c.getString(c.getColumnIndexOrThrow("args_json"))) as? kotlinx.serialization.json.JsonObject }.getOrNull()),
    status = c.getString(c.getColumnIndexOrThrow("status")),
  )

  private fun ensureChannel() {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH))
  }

  private fun notify(pa: PendingAction, reason: String) {
    if (!notificationsAvailable()) return
    val nm = context.getSystemService(NotificationManager::class.java)
    fun action(decision: String, label: String): Notification.Action {
      val i = Intent(context, ApprovalReceiver::class.java).setAction(ApprovalReceiver.ACTION_RESOLVE)
        .putExtra(ApprovalReceiver.EXTRA_ID, pa.id).putExtra(ApprovalReceiver.EXTRA_DECISION, decision)
      // Distinct request codes per PendingIntent: extras are not part of PendingIntent identity.
      val pi = PendingIntent.getBroadcast(context, requestCodes.incrementAndGet(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      return Notification.Action.Builder(null, label, pi).build()
    }
    val summary = pa.args.entries.take(3).joinToString(", ") { (k, v) -> "$k=${v.toString().take(40)}" }
    val n = Notification.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_dialog_alert)
      .setContentTitle("Approve ${pa.capability}?")
      .setContentText("$reason · $summary")
      .setStyle(Notification.BigTextStyle().bigText("${pa.actionClass} · $reason\n$summary\nExpires in ${(pa.expiresAt - Ids.now()) / 60000} min"))
      .setCategory(Notification.CATEGORY_ALARM)
      .setAutoCancel(false)
      .setOngoing(true)
      .addAction(action("approve", "Approve"))
      .addAction(action("deny", "Deny"))
      .build()
    nm.notify(pa.id.hashCode(), n)
  }

  private fun cancelNotification(id: String) {
    context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
  }

  companion object { const val CHANNEL = "symbiosis.approvals" }
}
