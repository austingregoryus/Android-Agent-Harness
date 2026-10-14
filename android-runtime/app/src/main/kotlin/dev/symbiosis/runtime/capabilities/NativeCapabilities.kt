package dev.symbiosis.runtime.capabilities

import android.content.Context
import android.os.PowerManager
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.FilterClause
import dev.symbiosis.runtime.events.SubscriptionStore
import dev.symbiosis.runtime.events.Topics
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.sessions.CallSessions
import dev.symbiosis.runtime.sessions.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Capabilities the substrate does not provide: call sessions, events/subscriptions, device housekeeping. */
object NativeCapabilities {

  fun telephony(calls: CallSessions, sessions: SessionManager): List<Capability> = listOf(
    Capability(
      name = "telephony.place_call",
      description = "Place an outbound cellular call and open a call session. Returns session_id; track it with telephony.get_session, the session WebSocket, or call.* events.",
      params = listOf(
        Param("target", "string", "E.164 phone number to dial", required = true),
        Param("display_name", "string", "Human label for the callee"),
        Param("purpose", "string", "Why the call is being placed (audited)"),
        Param("driver_managed", "boolean", "Cancel approval when the caller stops waiting; end the call if conversation mode does not start within 90 seconds (default false)"),
        Param("audio_route", "string", "earpiece (default) or speaker", enum = listOf("earpiece", "speaker")),
      ),
      // An outbound call commits the user to the world: EXTERNAL_COMMITMENT, unattended by default (docs/security.md#configure-the-defaults-deliberately) — rate limits and the denylist are the brakes, not an approval tap.
      actionClass = ActionClass.EXTERNAL_COMMITMENT, rateClass = RateClass.CALL,
      requiredPermissions = listOf("android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE"),
      emitsEvents = listOf(Topics.SESSION_STATE, Topics.CALL_CONNECTED, Topics.CALL_ENDED),
      targetArg = "target",
    ) { args, ctx ->
      val target = args["target"]?.toString()?.trim().orEmpty()
      if (target.isEmpty()) return@Capability CapabilityResult.fail("invalid_params", "target is required")
      try {
        val s = calls.placeCall(target, args["display_name"]?.toString(), args["purpose"]?.toString(), args["audio_route"]?.toString(), ctx.peerId, ctx.correlationId, driverManaged = args["driver_managed"] == true)
        CapabilityResult.ok(mapOf("session_id" to s.sessionId, "state" to s.state.name, "target" to target))
      } catch (e: SecurityException) {
        CapabilityResult.missingPermission("android.permission.${e.message}")
      } catch (e: IllegalArgumentException) {
        CapabilityResult.fail("invalid_params", "target must be E.164 or a plain national number (digits only, optional leading +)")
      } catch (e: IllegalStateException) {
        val m = e.message ?: "call.failed"
        if (m.startsWith("call.busy")) CapabilityResult.fail("capability.failed", "another call session is active", mapOf("session_id" to m.substringAfter(':')))
        else CapabilityResult.fail("capability.failed", m)
      }
    },
    Capability(
      name = "telephony.end_call",
      description = "End the call for a session (best effort: Android may only allow the default dialer to hang up).",
      params = listOf(Param("session_id", "string", "Session to end", required = true)),
      actionClass = ActionClass.LOW_RISK,
      requiredPermissions = listOf("android.permission.ANSWER_PHONE_CALLS"),
      emitsEvents = listOf(Topics.SESSION_STATE, Topics.CALL_ENDED),
    ) { args, ctx ->
      val id = args["session_id"]?.toString() ?: return@Capability CapabilityResult.fail("invalid_params", "session_id is required")
      if (!owns(sessions, id, ctx)) return@Capability CapabilityResult.fail("session.unknown", id)
      try {
        val ok = calls.endCall(id)
        if (ok) CapabilityResult.ok(mapOf("session_id" to id, "ended" to true))
        else CapabilityResult.unavailable("TelecomManager.endCall refused on this device; the call must be ended from the dialer or by the far end")
      } catch (e: SecurityException) { CapabilityResult.missingPermission("android.permission.${e.message}") }
      catch (e: IllegalArgumentException) { CapabilityResult.fail("session.unknown", id) }
    },
    Capability(
      name = "telephony.set_audio_route",
      description = "Route call audio to the speaker or earpiece (best effort for calls owned by the system dialer).",
      params = listOf(Param("session_id", "string", "Session", required = true), Param("route", "string", "speaker or earpiece", required = true, enum = listOf("speaker", "earpiece"))),
      actionClass = ActionClass.LOW_RISK,
    ) { args, ctx ->
      // Owned AND live: Telecom routes whatever call is up, so an owner of a stale session must not be able to
      // flip the route of a later call (possibly the user's own).
      val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
      if (access is SessionAccess.Access.Denied) return@Capability access.result
      val id = (access as SessionAccess.Access.Ok).session.sessionId
      val route = args["route"]?.toString() ?: "earpiece"
      val ok = calls.setAudioRoute(id, route)
      if (ok) CapabilityResult.ok(mapOf("session_id" to id, "route" to route)) else CapabilityResult.unavailable("audio route change not applied by the platform")
    },
    Capability(
      name = "telephony.dial_dtmf",
      description = "Send DTMF tones (0-9, *, #) on the active call, e.g. to navigate a phone menu. Works while our InCallService is bound to the call (the privapp install); otherwise capability.unavailable.",
      params = listOf(Param("session_id", "string", "Session", required = true), Param("digits", "string", "Digits 0-9*#", required = true)),
      actionClass = ActionClass.LOW_RISK,
    ) { args, ctx ->
      // Holder.active() is whatever call Telecom has up, not necessarily this session's: without the live-call
      // check, the owner of a stale session could key tones into the user's own call.
      val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
      if (access is SessionAccess.Access.Denied) return@Capability access.result
      val id = (access as SessionAccess.Access.Ok).session.sessionId
      val digits =args["digits"]?.toString()?.filter { it in "0123456789*#" } ?: ""
      if (digits.isEmpty()) return@Capability CapabilityResult.fail("invalid_params", "digits must be 0-9, * or #")
      val call = dev.symbiosis.runtime.telephony.SymbiosisInCallService.Holder.active()
        ?: return@Capability CapabilityResult.unavailable("dtmf needs the default-dialer role (InCallService not bound)")
      for (d in digits) { call.playDtmfTone(d); kotlinx.coroutines.delay(160); call.stopDtmfTone(); kotlinx.coroutines.delay(90) }
      sessions.emitFrame(id, sessions.get(id)?.correlationId, "dtmf.sent", kotlinx.serialization.json.buildJsonObject { put("digits", kotlinx.serialization.json.JsonPrimitive(digits)) })
      CapabilityResult.ok(mapOf("session_id" to id, "digits" to digits))
    },
    Capability(
      name = "telephony.get_session",
      description = "Get the current state and summary of a call session.",
      params = listOf(Param("session_id", "string", "Session", required = true)),
      actionClass = ActionClass.READ_ONLY,
    ) { args, ctx ->
      val id = args["session_id"]?.toString() ?: return@Capability CapabilityResult.fail("invalid_params", "session_id is required")
      val s = sessions.get(id)?.takeIf { owns(it, ctx) } ?: return@Capability CapabilityResult.fail("session.unknown", id)
      CapabilityResult.ok(Json.toMap(sessions.toJson(s)))
    },
    Capability(
      name = "telephony.list_sessions",
      description = "List recent call sessions.",
      params = listOf(Param("limit", "integer", "Max sessions (default 20)")),
      actionClass = ActionClass.READ_ONLY,
    ) { args, ctx ->
      val limit = (args["limit"] as? Number)?.toInt() ?: 20
      CapabilityResult.ok(mapOf("sessions" to sessions.recent(limit).filter { owns(it, ctx) }.map { Json.toMap(sessions.toJson(it)) }))
    },
  )

  /** Sessions are visible to their owning peer and to the admin; sessions without an owner are admin-only. */
  private fun owns(s: dev.symbiosis.runtime.sessions.Session, ctx: CallContext): Boolean =
    ctx.principal?.isAdmin == true || (s.peerId != null && s.peerId == ctx.peerId)
  private fun owns(sessions: SessionManager, id: String, ctx: CallContext): Boolean = sessions.get(id)?.let { owns(it, ctx) } ?: false

  private fun scopeError(glob: String) = CapabilityResult.fail("policy.denied", "topic '$glob' is outside this peer's scopes (needs ${dev.symbiosis.runtime.capabilities.Scopes.requiredScopesForTopicGlob(glob).joinToString(" and ")})")

  fun events(broker: EventBroker, subs: SubscriptionStore): List<Capability> = listOf(
    Capability(
      name = "events.subscribe",
      description = "Create a persistent subscription. Events are pushed on /ws/events and can be pulled with events.replay.",
      params = listOf(
        Param("topic_glob", "string", "e.g. messaging.sms_received, call.*, *", required = true),
        Param("filter", "array", "Array of {field, op, value}; op in eq, ne, lt, lte, gt, gte, contains, prefix"),
        Param("ttl_ms", "integer", "Auto-expire after this many ms"),
        Param("delivery", "string", "live (default) or queue", enum = listOf("live", "queue")),
      ),
      actionClass = ActionClass.LOW_RISK, tier = 0,
    ) { args, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      val glob = args["topic_glob"]?.toString() ?: "*"
      if (ctx.principal?.canSubscribe(glob) != true) return@Capability scopeError(glob)
      val filter = parseFilter(args["filter"])
      val sub = try { subs.create(peer, glob, filter, args["delivery"]?.toString() ?: "live", (args["ttl_ms"] as? Number)?.toLong()) }
      catch (e: IllegalStateException) { return@Capability CapabilityResult.fail("subscription.limit", e.message) }
      CapabilityResult.ok(mapOf("subscription_id" to sub.subscription_id, "created_at_event_id" to sub.created_at_event_id, "expires_at" to sub.expires_at))
    },
    Capability(
      name = "events.unsubscribe", description = "Delete a subscription.",
      params = listOf(Param("subscription_id", "string", "Subscription", required = true)),
      actionClass = ActionClass.LOW_RISK, tier = 0,
    ) { args, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      CapabilityResult.ok(mapOf("removed" to subs.delete(peer, args["subscription_id"].toString())))
    },
    Capability(
      name = "events.list_subscriptions", description = "List this peer's subscriptions.", params = emptyList(),
      actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { _, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      CapabilityResult.ok(mapOf("subscriptions" to subs.listFor(peer).map { subJson(it) }))
    },
    Capability(
      name = "events.ack", description = "Cumulatively acknowledge events through an id for a subscription.",
      params = listOf(Param("subscription_id", "string", "Subscription", required = true), Param("through_id", "integer", "Highest event id processed", required = true)),
      actionClass = ActionClass.LOW_RISK, tier = 0,
    ) { args, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      subs.ack(peer, args["subscription_id"].toString(), (args["through_id"] as? Number)?.toLong() ?: 0)
      CapabilityResult.ok(mapOf("ok" to true))
    },
    Capability(
      name = "events.replay",
      description = "Return matching events after since_id for a subscription (default since the ack cursor). Use this to poll without a WebSocket.",
      params = listOf(
        Param("subscription_id", "string", "Subscription", required = true),
        Param("since_id", "integer", "Event id to resume after; defaults to the subscription's ack cursor"),
        Param("limit", "integer", "Max events (default 200, max 1000)"),
      ),
      actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { args, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      val sub = subs.get(peer, args["subscription_id"].toString()) ?: return@Capability CapabilityResult.fail("subscription.unknown")
      val since = (args["since_id"] as? Number)?.toLong() ?: sub.ack_cursor
      val principal = ctx.principal
      val r = subs.replay(sub, since, (args["limit"] as? Number)?.toInt() ?: 200) { ev -> principal?.canSeeTopic(ev.topic) == true }
      CapabilityResult.ok(
        mapOf(
          "events" to r.events.map { Json.toMap(Json.lenient.encodeToJsonElement(dev.symbiosis.runtime.events.Event.serializer(), it) as JsonObject) },
          "next_since_id" to r.nextSinceId, "truncated" to r.truncated, "earliest_available_id" to r.earliestAvailableId,
        ),
      )
    },
    Capability(
      name = "events.wait",
      description = "Block up to timeout_ms for the next event matching topic_glob and filter (max 60 s). Returns the event or timed_out=true. Prefer a subscription for anything longer.",
      params = listOf(
        Param("topic_glob", "string", "Topic glob", required = true),
        Param("filter", "array", "Array of {field, op, value}"),
        Param("timeout_ms", "integer", "Max wait (default 30000, max 60000)"),
      ),
      actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { args, ctx ->
      val glob = args["topic_glob"]?.toString() ?: "*"
      val principal = ctx.principal ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      if (!principal.canSubscribe(glob)) return@Capability scopeError(glob)
      val filter = parseFilter(args["filter"])
      val timeout = ((args["timeout_ms"] as? Number)?.toLong() ?: 30_000L).coerceIn(100, 60_000)
      val ev = withTimeoutOrNull(timeout) {
        broker.live.first { dev.symbiosis.runtime.events.TopicGlob.matches(glob, it.topic) && principal.canSeeTopic(it.topic) && filter.all { f -> f.matches(it.payload) } }
      }
      if (ev == null) CapabilityResult.ok(mapOf("timed_out" to true))
      else CapabilityResult.ok(mapOf("timed_out" to false, "event" to Json.toMap(Json.lenient.encodeToJsonElement(dev.symbiosis.runtime.events.Event.serializer(), ev) as JsonObject)))
    },
    Capability(
      name = "messaging.watch_sms",
      description = "Sugar for events.subscribe on messaging.sms_received, optionally filtered by sender prefix/contains and a TTL.",
      params = listOf(Param("from", "string", "Sender number (matched by contains)"), Param("ttl_ms", "integer", "Auto-expire (default 5 minutes)")),
      actionClass = ActionClass.LOW_RISK, tier = 0,
    ) { args, ctx ->
      val peer = ctx.peerId ?: return@Capability CapabilityResult.fail("unauthorized", "no peer")
      if (ctx.principal?.canSubscribe(Topics.SMS_RECEIVED) != true) return@Capability scopeError(Topics.SMS_RECEIVED)
      val filter = args["from"]?.toString()?.takeIf { it.isNotBlank() }?.let { listOf(FilterClause("from", "contains", kotlinx.serialization.json.JsonPrimitive(it.filter { c -> c.isDigit() }))) } ?: emptyList()
      val sub = try { subs.create(peer, Topics.SMS_RECEIVED, filter, "live", (args["ttl_ms"] as? Number)?.toLong() ?: 300_000L) }
      catch (e: IllegalStateException) { return@Capability CapabilityResult.fail("subscription.limit", e.message) }
      CapabilityResult.ok(mapOf("subscription_id" to sub.subscription_id, "created_at_event_id" to sub.created_at_event_id, "expires_at" to sub.expires_at))
    },
  )

  fun device(context: Context, runtimeStatus: () -> Map<String, Any?>): List<Capability> = listOf(
    Capability(
      name = "device.get_status", description = "Runtime status: server, uptime, permissions, event journal, active sessions, policy flags.",
      params = emptyList(), actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { _, _ -> CapabilityResult.ok(runtimeStatus()) },
    Capability(
      name = "device.keep_awake",
      description = "Hold one partial wake lock per peer for up to 30 minutes (a new call replaces the previous lock). Rate limited.",
      params = listOf(Param("seconds", "integer", "Duration (default 300, max 1800); 0 releases the lock")),
      actionClass = ActionClass.LOW_RISK, tier = 0, rateClass = RateClass.WAKE,
    ) { args, ctx ->
      val secs = ((args["seconds"] as? Number)?.toLong() ?: 300L).coerceIn(0, 1800)
      val key = ctx.peerId ?: "anonymous"
      synchronized(wakeLocks) {
        wakeLocks.remove(key)?.let { runCatching { if (it.isHeld) it.release() } }
        if (secs > 0) {
          val wl = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "symbiosis:keep_awake:$key")
          wl.acquire(secs * 1000)
          wakeLocks[key] = wl
        }
      }
      CapabilityResult.ok(mapOf("held_seconds" to secs, "until" to if (secs > 0) Ids.now() + secs * 1000 else null))
    },
  )

  private val wakeLocks = HashMap<String, PowerManager.WakeLock>()

  private fun parseFilter(raw: Any?): List<FilterClause> {
    val el = Json.toJson(raw)
    if (el !is JsonArray) return emptyList()
    return runCatching { Json.lenient.decodeFromJsonElement(ListSerializer(FilterClause.serializer()), el) }.getOrDefault(emptyList())
  }

  fun subJson(s: dev.symbiosis.runtime.events.Subscription): Map<String, Any?> = mapOf(
    "subscription_id" to s.subscription_id, "topic_glob" to s.topic_glob, "delivery" to s.delivery,
    "filter" to Json.fromJson(Json.lenient.encodeToJsonElement(ListSerializer(FilterClause.serializer()), s.filter)),
    "created_at" to s.created_at, "created_at_event_id" to s.created_at_event_id, "expires_at" to s.expires_at, "ack_cursor" to s.ack_cursor,
  )
}
