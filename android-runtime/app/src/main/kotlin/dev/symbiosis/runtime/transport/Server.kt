package dev.symbiosis.runtime.transport

import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Event
import dev.symbiosis.runtime.events.FilterClause
import dev.symbiosis.runtime.events.Subscription
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.SessionFrameCursor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.contentCharset
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor Netty server: /health, /pair, /mcp (Streamable HTTP), /ws/events, /ws/sessions/{id}.
 * Plain HTTP binds loopback only; the optional TLS connector (self-signed, fingerprint-pinned) is the only network-facing listener.
 */
class Server(private val runtime: Runtime, private val host: String, private val port: Int, private val tls: io.droidmcp.core.transport.TlsConfig? = null) {
  private var engine: EmbeddedServer<*, *>? = null
  private val mcp = McpHandler(runtime)
  private val eventSockets = ConcurrentHashMap<Long, EventSocket>()
  private val socketIds = AtomicLong(0)
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile var running = false; private set
  val url get() = "http://$host:$port"
  val tlsUrl get() = tls?.let { "https://0.0.0.0:${it.httpsPort}" }

  /** One /ws/events connection. Subscriptions are the peer's persisted set, refreshed when the store version changes. */
  private class EventSocket(val id: Long, val principal: Principal, val outbound: Channel<JsonObject>, val job: Job) {
    @Volatile var subs: List<Subscription> = emptyList()
    @Volatile var subsVersion: Long = -1
    /**
     * subscription_id → (from_id, to_id, announced) of events not pushed because the socket was slow. The writer
     * announces each gap once (announced = 1); the subscription stays paused until the client replays.
     */
    val gaps = ConcurrentHashMap<String, LongArray>()
  }

  /** One /ws/sessions/{id} connection, tracked so revocation and emergency stop can close it. */
  private class SessionSocket(val peerId: String, val ws: DefaultWebSocketServerSession)
  private val sessionSockets = ConcurrentHashMap<Long, SessionSocket>()

  /** Owner of a session named in an event payload, for [Principal.canSeeEvent]. */
  private val sessionOwner: (String) -> String? = { runtime.sessions.ownerOf(it) }

  init {
    runtime.subscriptions.onReplayed = { peerId, subId -> eventSockets.values.filter { it.principal.peerId == peerId }.forEach { it.gaps.remove(subId) } }
  }

  fun start() {
    if (running) return
    engine = embeddedServer(
      Netty,
      configure = {
        // Android's SSLEngine has no JDK-style ALPN, and Netty's HTTP/2 negotiation asserts on it; HTTP/1.1 over TLS is all we need.
        enableHttp2 = false
        // Plain HTTP is loopback-only; the TLS connector (when enabled) is the only thing reachable from a network.
        connector { this.host = this@Server.host; this.port = this@Server.port }
        tls?.let { t ->
          sslConnector(keyStore = t.keyStore, keyAlias = t.keyAlias, keyStorePassword = { t.keyStorePassword }, privateKeyPassword = { t.privateKeyPassword }) {
            this.host = "0.0.0.0"; this.port = t.httpsPort
          }
        }
      },
    ) { module() }.also { it.start(wait = false) }
    running = true
  }

  fun stop() {
    running = false
    eventSockets.values.forEach { it.outbound.close(); it.job.cancel() }
    eventSockets.clear()
    engine?.stop(500, 1500)
    engine = null
  }

  fun closeSocketsFor(peerId: String, reason: String) {
    eventSockets.values.filter { it.principal.peerId == peerId }.forEach { s ->
      s.outbound.trySend(buildJsonObject { put("type", "bye"); put("reason", reason) })
      s.outbound.close()
    }
    // Session sockets too: a revoked peer kept streaming its call's frames (transcripts included) for as long as
    // it held a /ws/sessions socket open, because only /ws/events was tracked.
    closeSessionSockets(reason) { it.peerId == peerId }
    mcp.endSessionsFor(peerId)
  }

  fun closeAllSockets(reason: String) {
    eventSockets.values.forEach { s -> s.outbound.trySend(buildJsonObject { put("type", "bye"); put("reason", reason) }); s.outbound.close() }
    closeSessionSockets(reason) { true }
  }

  private fun closeSessionSockets(reason: String, which: (SessionSocket) -> Boolean) {
    sessionSockets.entries.filter { which(it.value) }.forEach { (key, s) ->
      sessionSockets.remove(key)
      scope.launch { runCatching { s.ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(100))) } }
    }
  }

  /** True while [peerId] holds any open event or session socket: the driver lease counts that as presence. */
  fun hasSocketsFor(peerId: String): Boolean =
    eventSockets.values.any { it.principal.peerId == peerId } || sessionSockets.values.any { it.peerId == peerId }

  fun stats(): Map<String, Any?> = mapOf("event_sockets" to eventSockets.size, "mcp_sessions" to mcp.sessionCount(), "url" to url, "tls_url" to tlsUrl, "tls_fingerprint_sha256" to tls?.certFingerprintSha256)

  private fun Application.module() {
    install(WebSockets) { pingPeriod = 20.seconds; timeoutMillis = 60_000; maxFrameSize = 1 shl 20 }
    // Browser-origin requests are refused outright: the API is for agents, and with `adb forward` a web page on the
    // workstation could otherwise reach /pair and /health as simple cross-origin requests.
    install(createApplicationPlugin("OriginGuard") {
      onCall { call ->
        val origin = call.request.header("Origin")
        if (origin != null && origin !in ALLOWED_ORIGINS) {
          call.respondText(errJson("browser origins are not allowed"), ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
      }
    })

    // Fan-out: one collector for all event sockets. Each event is encoded once; per-event failures are logged, never fatal.
    launch {
      var lastId = 0L
      runtime.broker.live.collect { ev ->
        // The broker drops a slow collector's oldest events (EventBroker.live) and publishes in id order, so a
        // jump in ids means this collector missed events: pause every live subscription over the hole with a gap
        // frame, exactly as for a slow socket, and let clients replay (replay re-applies each subscription's own
        // filter, so a gap on a subscription that would not have matched costs one empty replay).
        if (lastId in 1 until ev.id - 1) runCatching { markMissed(lastId + 1, ev.id - 1) }
        if (ev.id > lastId) lastId = ev.id
        try { fanOut(ev) } catch (t: Throwable) { android.util.Log.e("Server", "fan-out failed for event ${ev.id}", t) }
      }
    }

    routing {
      get("/health") {
        call.respondText(
          Json.lenient.encodeToString(JsonElement.serializer(), buildJsonObject { put("ok", true); put("device_id", runtime.deviceId); put("version", runtime.version); put("protocol", "0.1"); put("tls_fingerprint_sha256", tls?.certFingerprintSha256) }),
          ContentType.Application.Json,
        )
      }

      post("/pair") {
        if (tooLarge(call)) return@post call.respondText(errJson("body too large"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
        val raw = readBodyCapped(call) ?: return@post call.respondText(errJson("body too large"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
        val body = runCatching { Json.lenient.parseToJsonElement(raw) as JsonObject }.getOrNull()
          ?: return@post call.respondText(errJson("invalid json"), ContentType.Application.Json, HttpStatusCode.BadRequest)
        val code = body["code"]?.jsonPrimitive?.contentOrNull ?: return@post call.respondText(errJson("code is required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
        val label = body["label"]?.jsonPrimitive?.contentOrNull ?: "peer"
        val scopes = (body["scopes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val maxRaw = body["max_action_class"]?.jsonPrimitive?.contentOrNull
        val max = ActionClass.parse(maxRaw)
        if (maxRaw != null && max == null) return@post call.respondText(errJson("unknown max_action_class"), ContentType.Application.Json, HttpStatusCode.BadRequest)
        if (!pairAttemptAllowed()) return@post call.respondText(errJson("too many pairing attempts; try again later"), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
        val res = runtime.peers.redeem(code, label, scopes, max)
        if (res == null) {
          runtime.audit.append(dev.symbiosis.runtime.audit.AuditEntry(ts = Ids.now(), correlationId = null, peerId = null, kind = dev.symbiosis.runtime.audit.AuditKind.ADMIN, capability = "pair", success = false, errorCode = "pair.invalid_code", args = buildJsonObject { put("label", label) }))
          return@post call.respondText(errJson("invalid, used, expired, or out-of-grant code/scopes"), ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
        val (peer, token) = res
        runtime.audit.append(dev.symbiosis.runtime.audit.AuditEntry(ts = Ids.now(), correlationId = null, peerId = peer.peerId, kind = dev.symbiosis.runtime.audit.AuditKind.ADMIN, capability = "pair", success = true, args = buildJsonObject { put("label", label); put("scopes", JsonArray(peer.scopes.map { JsonPrimitive(it) })); put("max_action_class", peer.maxActionClass.name) }))
        call.respondText(
          Json.lenient.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("peer_id", peer.peerId); put("token", token); put("scopes", JsonArray(peer.scopes.map { JsonPrimitive(it) }))
            put("max_action_class", peer.maxActionClass.name); put("expires_at", peer.expiresAt); put("device_id", runtime.deviceId); put("mcp_url", "$url/mcp"); put("events_url", "ws://$host:$port/ws/events")
            tls?.let { t -> put("tls_mcp_url", "https://<phone-ip>:${t.httpsPort}/mcp"); put("tls_events_url", "wss://<phone-ip>:${t.httpsPort}/ws/events"); put("tls_fingerprint_sha256", t.certFingerprintSha256) }
          }),
          ContentType.Application.Json,
        )
      }

      post("/mcp") {
        val principal = authenticate(call) ?: return@post unauthorized(call)
        if (tooLarge(call)) return@post call.respondText(errJson("body too large"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
        val text = readBodyCapped(call) ?: return@post call.respondText(errJson("body too large"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
        val body = runCatching { Json.lenient.parseToJsonElement(text) }.getOrNull()
          ?: return@post call.respondText("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse error"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        val pv = call.request.header("MCP-Protocol-Version")
        when (val r = mcp.handle(principal, body, call.request.header("Mcp-Session-Id"), pv, call.request.header("Mcp-Correlation-Id"))) {
          is McpResponse.Accepted -> call.respond(HttpStatusCode.Accepted)
          is McpResponse.HttpError -> call.respondText(errJson(r.message), ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
          is McpResponse.Reply -> {
            r.sessionId?.let { call.response.header("Mcp-Session-Id", it) }
            pv?.let { call.response.header("MCP-Protocol-Version", it) }
            call.respondText(Json.lenient.encodeToString(JsonElement.serializer(), r.body), ContentType.Application.Json)
          }
        }
      }
      get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
      delete("/mcp") {
        val principal = authenticate(call) ?: return@delete unauthorized(call)
        val sid = call.request.header("Mcp-Session-Id")
        if (sid != null && mcp.endSession(sid, principal.peerId)) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.NotFound)
      }

      webSocket("/ws/events") {
        val principal = authenticate(call) ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
        if (eventSockets.values.count { it.principal.peerId == principal.peerId } >= MAX_SOCKETS_PER_PEER) { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many sockets")); return@webSocket }
        eventsSocket(principal)
      }

      webSocket("/ws/sessions/{id}") {
        val principal = authenticate(call) ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
        val id = call.parameters["id"] ?: return@webSocket
        val session = runtime.sessions.get(id) ?: run { close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown session")); return@webSocket }
        // Ownership: the creating peer or the admin. Sessions without an owner are admin-only.
        if (!principal.isAdmin && (session.peerId == null || session.peerId != principal.peerId)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not your session")); return@webSocket }
        val sockKey = socketIds.incrementAndGet()
        sessionSockets[sockKey] = SessionSocket(principal.peerId, this)
        var job: Job? = null
        // Registered before the backlog is sent and removed in the finally, so a client that drops mid-backlog
        // cannot leave a phantom socket behind (the driver lease would read it as the driver still being there).
        try {
          val since = call.request.queryParameters["since_seq"]?.toLongOrNull() ?: 0
          // Subscribe to live frames BEFORE reading the backlog (onSubscription runs once the collector is
          // registered), and let the cursor de-duplicate by seq: reading the backlog first lost every frame emitted
          // in between. The cursor also refills frames this collector missed because it was slow (the flow drops a
          // slow collector's oldest frames rather than stalling every socket) and sends `gap` when it cannot.
          val cursor = SessionFrameCursor(id, since, { after, limit -> runtime.sessions.framesAfter(id, after, limit) }, { runtime.sessions.headSeq(id) })
          suspend fun sendAll(frames: List<JsonObject>) { for (f in frames) send(Json.lenient.encodeToString(JsonElement.serializer(), f)) }
          job = launch {
            runCatching {
              runtime.sessions.frames
                .onSubscription { sendAll(cursor.backlog()) }
                .collect { f -> if (f.sessionId == id) sendAll(cursor.live(f)) }
            }
          }
          for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val msg = runCatching { Json.lenient.parseToJsonElement(frame.readText()) as JsonObject }.getOrNull() ?: continue
            when (msg["type"]?.jsonPrimitive?.contentOrNull) {
              "ping" -> send("""{"type":"pong"}""")
              "user.interrupt" -> runtime.sessions.emitFrame(id, session.correlationId, "user.interrupt", buildJsonObject { put("by", principal.peerId) })
            }
          }
        } catch (_: ClosedReceiveChannelException) { } finally {
          job?.cancel()
          sessionSockets.remove(sockKey)
          // The driver lease measures absence from the moment the last socket closed, not from the last request.
          runtime.peers.touch(principal.peerId)
        }
      }
    }
  }

  /** Push one event to every socket whose peer owns a matching, in-scope subscription. */
  /** Events [from]..[to] were dropped before fan-out saw them: open a gap on every live subscription not already paused. */
  private fun markMissed(from: Long, to: Long) {
    android.util.Log.w("Server", "event fan-out fell behind: events $from..$to not pushed live; announcing gaps")
    val version = runtime.subscriptions.version
    for (sock in eventSockets.values) {
      if (sock.subsVersion != version) { sock.subs = runtime.subscriptions.listFor(sock.principal.peerId); sock.subsVersion = version }
      for (sub in sock.subs) {
        if (sub.delivery == "queue" || sub.expired()) continue
        val range = longArrayOf(from, to, 0)
        if (sock.gaps.putIfAbsent(sub.subscription_id, range) != null) continue
        // Announce now: unlike a full socket, this one may have nothing else queued to wake the writer's gap check.
        val frame = buildJsonObject { put("type", "gap"); put("subscription_id", sub.subscription_id); put("from_id", from); put("to_id", to) }
        if (sock.outbound.trySend(frame).isSuccess) range[2] = 1
      }
    }
  }

  private fun fanOut(ev: Event) {
    var encoded: JsonElement? = null
    val version = runtime.subscriptions.version
    for (sock in eventSockets.values) {
      if (!sock.principal.canSeeEvent(ev, sessionOwner)) continue
      if (sock.subsVersion != version) { sock.subs = runtime.subscriptions.listFor(sock.principal.peerId); sock.subsVersion = version }
      for (sub in sock.subs) {
        if (sub.delivery == "queue") continue // replay-only by contract (protocol.md §4.3)
        if (sub.expired() || !sub.matches(ev)) continue
        val sid = sub.subscription_id
        // Paused until the client replays. One lookup: replay can remove the gap between a containsKey and a get.
        val paused = sock.gaps[sid]
        if (paused != null) { paused[1] = ev.id; continue }
        if (encoded == null) encoded = Json.lenient.encodeToJsonElement(Event.serializer(), ev)
        val frame = buildJsonObject { put("type", "event"); put("subscription_id", sid); put("event", encoded) }
        if (!sock.outbound.trySend(frame).isSuccess) {
          // Slow consumer: stop pushing this subscription and let the writer announce the gap when it drains.
          sock.gaps[sid] = longArrayOf(ev.id, ev.id, 0)
        }
      }
    }
  }

  private suspend fun DefaultWebSocketServerSession.eventsSocket(principal: Principal) {
    val outbound = Channel<JsonObject>(capacity = 256)
    val id = socketIds.incrementAndGet()
    lateinit var sock: EventSocket
    val writer = launch {
      try {
        for (m in outbound) {
          send(Json.lenient.encodeToString(JsonElement.serializer(), m))
          // The channel has drained enough to write: announce any new gaps so the client can replay. Once each:
          // this used to re-send every open gap after every message until the client replayed, so a client busy
          // replaying got a gap frame per event it was sent. The subscription stays paused (fanOut keeps extending
          // to_id), and replay from from_id covers everything up to now whatever to_id said at announcement.
          for ((sid, range) in sock.gaps) {
            if (range[2] != 0L) continue
            range[2] = 1
            send(Json.lenient.encodeToString(JsonElement.serializer(), buildJsonObject { put("type", "gap"); put("subscription_id", sid); put("from_id", range[0]); put("to_id", range[1]) }))
          }
        }
      } catch (_: Throwable) { }
      runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
    }
    sock = EventSocket(id, principal, outbound, writer)
    eventSockets[id] = sock
    sock.subs = runtime.subscriptions.listFor(principal.peerId); sock.subsVersion = runtime.subscriptions.version
    outbound.trySend(buildJsonObject { put("type", "hello"); put("peer_id", principal.peerId); put("last_event_id", runtime.broker.lastId()); put("subscriptions", JsonArray(sock.subs.map { JsonPrimitive(it.subscription_id) })) })
    try {
      for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val msg = runCatching { Json.lenient.parseToJsonElement(frame.readText()) as JsonObject }.getOrNull()
          ?: run { outbound.trySend(err("invalid_json", "frame is not a JSON object")); continue }
        try { handleEventFrame(sock, principal, msg) } catch (t: Throwable) { outbound.trySend(err("internal", t.message ?: t.javaClass.simpleName)) }
      }
    } catch (_: ClosedReceiveChannelException) {
    } finally {
      eventSockets.remove(id)
      outbound.close()
      writer.cancel()
      runtime.peers.touch(principal.peerId)
    }
  }

  private fun handleEventFrame(sock: EventSocket, principal: Principal, msg: JsonObject) {
    val out = sock.outbound
    val ref = msg["ref"]
    fun reply(b: JsonObject) { out.trySend(if (ref != null) JsonObject(b + ("ref" to ref)) else b) }
    when (msg["type"]?.jsonPrimitive?.contentOrNull) {
      "ping" -> reply(buildJsonObject { put("type", "pong") })
      "subscribe" -> {
        val glob = msg["topic_glob"]?.jsonPrimitive?.contentOrNull ?: return reply(err("invalid_params", "topic_glob is required"))
        if (!principal.canSubscribe(glob)) return reply(err("policy.denied", "topic '$glob' is outside this peer's scopes"))
        val filter = (msg["filter"] as? JsonArray)?.let { runCatching { Json.lenient.decodeFromJsonElement(ListSerializer(FilterClause.serializer()), it) }.getOrNull() } ?: emptyList()
        val sub = try { runtime.subscriptions.create(principal.peerId, glob, filter, msg["delivery"]?.jsonPrimitive?.contentOrNull ?: "live", msg["ttl_ms"]?.jsonPrimitive?.longOrNull) }
        catch (e: IllegalStateException) { return reply(err("subscription.limit", e.message ?: "limit")) }
        reply(buildJsonObject { put("type", "subscribed"); put("subscription_id", sub.subscription_id); put("created_at_event_id", sub.created_at_event_id); put("expires_at", sub.expires_at) })
      }
      "unsubscribe" -> {
        val sid = msg["subscription_id"]?.jsonPrimitive?.contentOrNull ?: return reply(err("invalid_params", "subscription_id is required"))
        sock.gaps.remove(sid)
        reply(buildJsonObject { put("type", "unsubscribed"); put("subscription_id", sid); put("removed", runtime.subscriptions.delete(principal.peerId, sid)) })
      }
      "ack" -> {
        val sid = msg["subscription_id"]?.jsonPrimitive?.contentOrNull ?: return
        val through = msg["through_id"]?.jsonPrimitive?.longOrNull ?: return
        runtime.subscriptions.ack(principal.peerId, sid, through)
      }
      "replay" -> {
        val sid = msg["subscription_id"]?.jsonPrimitive?.contentOrNull ?: return reply(err("invalid_params", "subscription_id is required"))
        val sub = runtime.subscriptions.get(principal.peerId, sid) ?: return reply(err("subscription.unknown", sid))
        val since = msg["since_id"]?.jsonPrimitive?.longOrNull ?: sub.ack_cursor
        val r = runtime.subscriptions.replay(sub, since, (msg["limit"]?.jsonPrimitive?.longOrNull ?: 200L).toInt()) { principal.canSeeEvent(it, sessionOwner) }
        sock.gaps.remove(sid) // live push resumes after a replay
        reply(
          buildJsonObject {
            put("type", "replay_result"); put("subscription_id", sid)
            put("events", JsonArray(r.events.map { Json.lenient.encodeToJsonElement(Event.serializer(), it) }))
            put("next_since_id", r.nextSinceId); put("truncated", r.truncated); put("earliest_available_id", r.earliestAvailableId)
          },
        )
      }
      "list" -> reply(buildJsonObject { put("type", "subscriptions"); put("subscriptions", JsonArray(runtime.subscriptions.listFor(principal.peerId).map { Json.toJson(dev.symbiosis.runtime.capabilities.NativeCapabilities.subJson(it)) })) })
      else -> reply(err("unknown_type", "unknown frame type"))
    }
  }

  private fun err(code: String, message: String) = buildJsonObject { put("type", "error"); put("code", code); put("message", message) }
  private fun errJson(message: String) = Json.lenient.encodeToString(JsonElement.serializer(), buildJsonObject { put("error", message) })

  /** Fast refusal when the client declared an oversize Content-Length. Chunked bodies declare none; see [readBodyCapped]. */
  private fun tooLarge(call: ApplicationCall): Boolean = (call.request.contentLength() ?: 0L) > MAX_BODY_BYTES

  /**
   * The request body as text, or null once it exceeds [MAX_BODY_BYTES]. [tooLarge] only sees Content-Length, and
   * `receiveText()` buffers whatever arrives: a chunked POST to the unauthenticated /pair could stream until the
   * app ran out of memory. This reads at most MAX_BODY_BYTES + 1 bytes and stops.
   */
  private suspend fun readBodyCapped(call: ApplicationCall, max: Long = MAX_BODY_BYTES): String? {
    val ch = call.receiveChannel()
    val out = java.io.ByteArrayOutputStream(minOf(max, 64L * 1024).toInt())
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
      val n = ch.readAvailable(buf, 0, buf.size)
      if (n < 0) break
      total += n
      if (total > max) { ch.cancel(null); return null }
      out.write(buf, 0, n)
    }
    return out.toString((call.request.contentCharset() ?: Charsets.UTF_8).name())
  }

  // /pair is unauthenticated by design; cap failed attempts per minute so a 40-bit code cannot be brute-forced.
  private val pairAttempts = ArrayDeque<Long>()
  private fun pairAttemptAllowed(): Boolean = synchronized(pairAttempts) {
    val now = Ids.now()
    while (pairAttempts.isNotEmpty() && pairAttempts.first() < now - 60_000) pairAttempts.removeFirst()
    if (pairAttempts.size >= MAX_PAIR_ATTEMPTS_PER_MINUTE) return false
    pairAttempts.addLast(now); true
  }

  private fun authenticate(call: ApplicationCall): Principal? {
    // Header only: a token in the query string would land in proxy/access logs.
    val h = call.request.header("Authorization") ?: return null
    if (!h.startsWith("Bearer ", ignoreCase = true)) return null
    // PeerStore.authenticate records the request time, which the driver lease reads.
    return runtime.peers.authenticate(h.substring(7).trim())
  }

  private suspend fun unauthorized(call: ApplicationCall) {
    call.response.header("WWW-Authenticate", "Bearer")
    call.respondText("""{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
  }

  companion object {
    const val MAX_BODY_BYTES = 1L shl 20
    const val MAX_SOCKETS_PER_PEER = 4
    const val MAX_PAIR_ATTEMPTS_PER_MINUTE = 10
    /** No browser origin is allowed by default; extend deliberately if a local web UI is ever built. */
    val ALLOWED_ORIGINS: Set<String> = emptySet()
  }
}
