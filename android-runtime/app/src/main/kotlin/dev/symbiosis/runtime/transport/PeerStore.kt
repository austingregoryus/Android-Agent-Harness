package dev.symbiosis.runtime.transport

import android.content.ContentValues
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.storage.SecureStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class Peer(
  val peerId: String, val label: String, val scopes: List<String>, val maxActionClass: ActionClass,
  val createdAt: Long, val expiresAt: Long?, val revokedAt: Long?, val lastSeenAt: Long?,
) {
  fun principal() = Principal(peerId, label, scopes, maxActionClass)
}

data class PairingCode(val code: String, val createdAt: Long, val expiresAt: Long, val scopes: List<String>, val maxActionClass: ActionClass, val usedAt: Long?)

/** Bearer tokens (hashed at rest), pairing codes, and the admin principal. */
/** Scope set and ceiling for a standard agent peer (UI "Pair an agent", DEBUG_PAIR access=standard). */
val STANDARD_SCOPES = listOf(
  "messaging.*", "telephony.*", "contacts.*", "device.*", "events.*", "notifications.*", "connectivity.*",
  "apps.*", "location.*", "calendar.*", "sensors.*", "files.*", "ui.*", "media.*",
  // Safe to grant with everything else: a batch can only run steps this same scope list and ceiling allow.
  "automation.*",
  // ai.ask: sends a prompt to the owner's LAN model router. LOW_RISK, so read-only ceilings never reach it.
  "ai.*",
  // diagnostics.get_runtime_logs / get_exit_history: sensitive reads kept out of device.* so a narrow device scope stays narrow.
  "diagnostics.*",
)
val STANDARD_CEILING = ActionClass.EXTERNAL_COMMITMENT

class PeerStore(private val db: Db, private val secure: SecureStore) {
  private val listSer = ListSerializer(String.serializer())

  val adminPrincipal = Principal(ADMIN_ID, "admin (this phone)", listOf("*"), ActionClass.DESTRUCTIVE)

  /** Hook: called when a peer is revoked so sockets/sessions can be torn down. */
  var onRevoked: ((String) -> Unit)? = null

  /**
   * Last time each peer (admin included) authenticated a request or closed a socket, in memory only. The driver
   * lease reads it to tell a driver that is merely quiet from one that is gone; the persisted `last_seen_at` is a
   * coarser record and is not written for the admin.
   */
  private val activity = java.util.concurrent.ConcurrentHashMap<String, Long>()

  fun touch(peerId: String) { activity[peerId] = Ids.now() }

  /** When [peerId] was last seen making a request or holding a socket (this process only), or null if never. */
  fun lastActivity(peerId: String): Long? = activity[peerId]

  fun authenticate(token: String?): Principal? {
    if (token.isNullOrBlank()) return null
    if (constantTimeEquals(token, secure.adminToken)) return adminPrincipal.also { touch(it.peerId) }
    val hash = Ids.sha256Hex(token)
    val peer = db.readableDatabase.query("trusted_peers", null, "token_sha256 = ?", arrayOf(hash), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null } ?: return null
    val now = Ids.now()
    if (peer.revokedAt != null) return null
    if (peer.expiresAt != null && now > peer.expiresAt) return null
    db.writableDatabase.update("trusted_peers", ContentValues().apply { put("last_seen_at", now) }, "peer_id = ?", arrayOf(peer.peerId))
    touch(peer.peerId)
    return peer.principal()
  }

  /**
   * What a "standard" agent gets: every read family, messaging and telephony (calls and texts run without a tap by
   * default; "Ask me first" and the contact lists in PolicyConfig gate them), the screen, calendar, sensors, files. The ceiling is
   * EXTERNAL_COMMITMENT because place_call, send_sms and notifications.reply live there; below that ceiling they are invisible
   * (that is exactly what hid them from the first Hermes peer on 2026-09-18).
   */
  fun mintStandardCode(): PairingCode = mintPairingCode(STANDARD_SCOPES, STANDARD_CEILING)

  fun mintPairingCode(scopes: List<String>, maxActionClass: ActionClass, ttlMs: Long = 5 * 60_000): PairingCode {
    val now = Ids.now()
    val pc = PairingCode(Ids.pairingCode(), now, now + ttlMs, scopes, maxActionClass, null)
    db.writableDatabase.insertOrThrow(
      "pairing_codes", null,
      ContentValues().apply {
        put("code", pc.code); put("created_at", now); put("expires_at", pc.expiresAt)
        put("scopes_json", Json.lenient.encodeToString(listSer, scopes)); put("max_action_class", maxActionClass.name)
      },
    )
    return pc
  }

  fun activePairingCodes(): List<PairingCode> = db.readableDatabase.query("pairing_codes", null, "used_at IS NULL AND expires_at > ?", arrayOf(Ids.now().toString()), null, null, "created_at DESC")
    .use { c -> buildList { while (c.moveToNext()) add(PairingCode(c.getString(0), c.getLong(1), c.getLong(2), Json.lenient.decodeFromString(listSer, c.getString(3)), ActionClass.valueOf(c.getString(4)), null)) } }

  /** Redeem a code. Returns (peer, plaintext token) or null when the code is invalid/used/expired. Requested scopes are clamped to the code's. */
  /** Capability names used to decide whether a requested scope is subsumed by a granted one; set by the runtime. */
  var knownCapabilities: () -> Collection<String> = { emptyList() }

  /** `granted` subsumes `req` iff every known capability that `req` matches is also matched by `granted`. */
  private fun subsumes(granted: List<String>, req: String): Boolean {
    if (granted.contains("*") || granted.contains(req)) return true
    val names = knownCapabilities().filter { dev.symbiosis.runtime.capabilities.Scopes.matches(req, it) }
    if (names.isEmpty()) return false
    return names.all { n -> granted.any { dev.symbiosis.runtime.capabilities.Scopes.matches(it, n) } }
  }

  fun redeem(code: String, label: String, requestedScopes: List<String>?, requestedMax: ActionClass?): Pair<Peer, String>? {
    val norm = code.trim().uppercase().replace(" ", "").replace("-", "").let { if (it.length == 8) it.substring(0, 4) + "-" + it.substring(4) else it }
    val w = db.writableDatabase
    w.beginTransaction()
    try {
      val pc = w.query("pairing_codes", null, "code = ? AND used_at IS NULL AND expires_at > ?", arrayOf(norm, Ids.now().toString()), null, null, null).use { c ->
        if (c.moveToFirst()) PairingCode(c.getString(0), c.getLong(1), c.getLong(2), Json.lenient.decodeFromString(listSer, c.getString(3)), ActionClass.valueOf(c.getString(4)), null) else null
      } ?: return null
      // Requested scopes are clamped to what the code grants. A request that is entirely out of grant is refused
      // rather than silently widened to the code's full set.
      val scopes = if (requestedScopes == null) pc.scopes else requestedScopes.filter { subsumes(pc.scopes, it) }.also { if (it.isEmpty()) return null }
      val max = requestedMax?.let { if (it <= pc.maxActionClass) it else pc.maxActionClass } ?: pc.maxActionClass
      val token = Ids.token()
      val now = Ids.now()
      val peer = Peer(Ids.peer(), label.ifBlank { "peer" }.take(64), scopes, max, now, null, null, null)
      w.insertOrThrow(
        "trusted_peers", null,
        ContentValues().apply {
          put("peer_id", peer.peerId); put("label", peer.label); put("token_sha256", Ids.sha256Hex(token))
          put("scopes_json", Json.lenient.encodeToString(listSer, scopes)); put("max_action_class", max.name); put("created_at", now)
        },
      )
      w.update("pairing_codes", ContentValues().apply { put("used_at", now) }, "code = ?", arrayOf(norm))
      w.setTransactionSuccessful()
      return peer to token
    } finally { w.endTransaction() }
  }

  fun revoke(peerId: String): Boolean {
    val n = db.writableDatabase.update("trusted_peers", ContentValues().apply { put("revoked_at", Ids.now()) }, "peer_id = ? AND revoked_at IS NULL", arrayOf(peerId))
    if (n > 0) onRevoked?.invoke(peerId)
    return n > 0
  }

  /** Live principal for a peer id, or null when unknown, revoked, or expired (used to re-check late approvals). */
  fun principalFor(peerId: String): Principal? = db.readableDatabase.query("trusted_peers", null, "peer_id = ?", arrayOf(peerId), null, null, null)
    .use { c -> if (c.moveToFirst()) row(c) else null }
    ?.takeIf { it.revokedAt == null && (it.expiresAt == null || Ids.now() <= it.expiresAt) }?.principal()

  /** Retention for used/expired pairing codes. */
  fun pruneCodes() { db.writableDatabase.delete("pairing_codes", "used_at IS NOT NULL OR expires_at < ?", arrayOf((Ids.now() - 24 * 3600_000L).toString())) }

  fun peers(): List<Peer> = db.readableDatabase.query("trusted_peers", null, null, null, null, null, "created_at DESC").use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  private fun row(c: android.database.Cursor) = Peer(
    peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
    label = c.getString(c.getColumnIndexOrThrow("label")),
    scopes = Json.lenient.decodeFromString(listSer, c.getString(c.getColumnIndexOrThrow("scopes_json"))),
    maxActionClass = ActionClass.valueOf(c.getString(c.getColumnIndexOrThrow("max_action_class"))),
    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    expiresAt = if (c.isNull(c.getColumnIndexOrThrow("expires_at"))) null else c.getLong(c.getColumnIndexOrThrow("expires_at")),
    revokedAt = if (c.isNull(c.getColumnIndexOrThrow("revoked_at"))) null else c.getLong(c.getColumnIndexOrThrow("revoked_at")),
    lastSeenAt = if (c.isNull(c.getColumnIndexOrThrow("last_seen_at"))) null else c.getLong(c.getColumnIndexOrThrow("last_seen_at")),
  )

  private fun constantTimeEquals(a: String, b: String): Boolean {
    val x = a.toByteArray(); val y = b.toByteArray()
    if (x.size != y.size) return false
    var r = 0
    for (i in x.indices) r = r or (x[i].toInt() xor y[i].toInt())
    return r == 0
  }

  companion object { const val ADMIN_ID = "peer_admin" }
}
