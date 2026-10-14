package dev.symbiosis.runtime.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.symbiosis.runtime.BuildConfig
import dev.symbiosis.runtime.audit.AuditEntry
import dev.symbiosis.runtime.audit.AuditKind
import dev.symbiosis.runtime.audit.AuditStore
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityRegistry
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.DroidMcpAdapter
import dev.symbiosis.runtime.capabilities.NativeCapabilities
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.SubscriptionStore
import dev.symbiosis.runtime.events.Topics
import dev.symbiosis.runtime.events.sources.CallStateSource
import dev.symbiosis.runtime.events.sources.NotificationEventListener
import dev.symbiosis.runtime.events.sources.SystemStateSource
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.ApprovalManager
import dev.symbiosis.runtime.policy.PendingAction
import dev.symbiosis.runtime.policy.PolicyEngine
import dev.symbiosis.runtime.policy.PolicyOutcome
import dev.symbiosis.runtime.policy.RateLimiter
import dev.symbiosis.runtime.sessions.CallSessions
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.storage.SecureStore
import dev.symbiosis.runtime.transport.PeerStore
import dev.symbiosis.runtime.transport.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Composition root and the single tools/call pipeline:
 * scope (registry) → policy → audit intent → execute → audit outcome. Lives as a process singleton so
 * receivers/services/UI share one instance; the foreground service owns the server lifecycle.
 */
class Runtime private constructor(private val context: Context) {
  val version: String = BuildConfig.VERSION_NAME
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val db = Db(context)
  val secure = SecureStore(context)
  val deviceId: String = secure.deviceId
  val audit = AuditStore(db, secure.auditSeed)
  val broker = EventBroker(db, deviceId)
  val subscriptions = SubscriptionStore(db, broker)
  val rateLimiter = RateLimiter(db)
  val policy = PolicyEngine(db, rateLimiter)
  val approvals = ApprovalManager(context, db)
  val sessions = SessionManager(db)
  val calls = CallSessions(context, sessions, broker, scope)
  val peers = PeerStore(db, secure)
  val registry = CapabilityRegistry()

  /** The one by-name entry point: McpHandler's tools/call and every automation.run step go through it. */
  private val toolDispatch = ToolDispatch(registry) { p, cap, args, ctx -> invoke(p, cap, args, ctx) }

  suspend fun dispatch(principal: Principal, name: String, args: Map<String, Any?>, ctx: CallContext): DispatchOutcome =
    toolDispatch.dispatch(principal, name, args, ctx)

  /**
   * Batches (automation.run) and saved workflows share one executor, and it holds nothing but dispatch() above — the
   * same path as a direct call. Steps run detached on the process scope so a batch deadline holds even when a
   * step's capability ignores cancellation.
   */
  private val automationExecutor = dev.symbiosis.runtime.automation.AutomationExecutor(
    { p, name, args, ctx -> dispatch(p, name, args, ctx) }, scope,
    // Re-resolved before every step: revocation, expiry and narrowed scopes take effect mid-batch.
    refresh = { p -> principalOf(p.peerId) },
  )

  /** A peer's current principal (the admin's is fixed), or null if revoked, expired or unknown. */
  fun principalOf(peerId: String): Principal? = if (peerId == PeerStore.ADMIN_ID) peers.adminPrincipal else peers.principalFor(peerId)

  /** Saved workflows and their event/schedule triggers (automation/WorkflowEngine.kt, docs/automation.md). */
  val workflows = dev.symbiosis.runtime.automation.WorkflowEngine(
    store = dev.symbiosis.runtime.automation.WorkflowStore(db),
    broker = broker,
    executor = automationExecutor,
    // The owner's CURRENT principal: a revoked or expired peer's workflows do not run (and get disabled).
    principalFor = { id -> principalOf(id) },
    emergencyStopped = { policy.emergencyStop.value },
    alarms = dev.symbiosis.runtime.automation.AndroidAlarms(context),
    scope = scope,
    audit = { peerId, corr, args, result, ok ->
      audit.append(AuditEntry(ts = Ids.now(), correlationId = corr, peerId = peerId, kind = AuditKind.WORKFLOW_RUN, capability = "automation.workflow", actionClass = ActionClass.AUTOMATION.name, args = args, result = result, success = ok))
    },
    // Switched off in the app = no triggered runs (alarms keep re-arming so they resume when it is switched on).
    gate = { RuntimeService.isEnabled(context) },
  )

  /**
   * The one thread every call-state callback runs on: TelephonyCallback's coarse IDLE/OFFHOOK and the
   * InCallService's precise states alike. It used to be `Dispatchers.IO.asExecutor()`, a pool, so an OFFHOOK and
   * the IDLE right after it could run concurrently or swap order and finish a call before it had connected.
   * Single-threaded keeps them in delivery order, and keeps finish()'s call-log wait off the main thread.
   */
  val callEvents: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "symbiosis-call-events").apply { isDaemon = true }
  }

  private val callState = CallStateSource(context, broker, calls)
  private val systemState = SystemStateSource(context, broker)
  private var server: Server? = null
  private var maintenanceJob: Job? = null
  private val startedAt = Ids.now()

  /** Idempotency keys currently executing, per peer, so a concurrent duplicate cannot run twice. */
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  /** Requests executing per peer. A driver blocked in a long request (a 20 s utterance) is present, not gone. */
  private val requestsInFlight = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

  private val _serverState = MutableStateFlow<String?>(null)
  val serverState: StateFlow<String?> = _serverState

  init {
    broker.onEmitted = { ev ->
      // Only side-effect topics are audited (battery/connectivity chatter would drown the log).
      if (auditsEvent(ev.topic, ev.payload)) {
        audit.append(AuditEntry(ts = ev.ts, correlationId = ev.correlation_id, peerId = null, kind = AuditKind.EVENT_EMITTED, capability = ev.topic, result = ev.payload, success = true))
      }
    }
    // Capabilities that act on a call must act on THE live call, not merely an owned non-terminal row.
    dev.symbiosis.runtime.capabilities.SessionAccess.liveCallId = { calls.currentSessionId() }
    sessions.onTransition = { s, from, to ->
      audit.append(AuditEntry(ts = Ids.now(), correlationId = s.correlationId, peerId = s.peerId, kind = AuditKind.SESSION_TRANSITION, capability = s.kind, args = buildJsonObject { put("session_id", s.sessionId); put("from", from.name); put("to", to.name) }, success = true))
      broker.emit(Topics.SESSION_STATE, mapOf("session_id" to s.sessionId, "kind" to s.kind, "state" to to.name, "previous_state" to from.name), s.correlationId)
      // A conversation lives exactly as long as its call. Tearing it down here — on every terminal transition,
      // whoever caused it — is what makes a stale conversation impossible rather than merely unlikely.
      if (to.terminal) dev.symbiosis.runtime.telephony.Conversations.stop(s.sessionId, "call ${to.name}")
    }
    // Side effects of the kill switch run off the caller's thread (it is usually the UI or a notification action).
    policy.onEmergencyStop = {
      scope.launch {
        // Before anything slower: no trigger may start a run from here on (the pipeline would refuse its steps
        // anyway, but a disabled workflow also stays off after the stop is released, until someone re-enables it).
        runCatching { workflows.disableAll("emergency_stop") }
        dev.symbiosis.runtime.telephony.Conversations.stopAll("emergency_stop")
        val cancelled = approvals.cancelPending(reason = "emergency_stop")
        runCatching { calls.endAll("emergency_stop") }
        server?.closeAllSockets("emergency_stop")
        audit.append(AuditEntry(ts = Ids.now(), correlationId = null, peerId = null, kind = AuditKind.ADMIN, capability = "emergency_stop", success = true, result = buildJsonObject { put("cancelled_pending", cancelled) }))
      }
    }
    peers.onRevoked = { peerId ->
      scope.launch {
        dev.symbiosis.runtime.telephony.Conversations.stopOwnedBy(peerId, "revoked")
        runCatching { workflows.onOwnerRevoked(peerId) }
        approvals.cancelPending(peerId, "revoked")
        server?.closeSocketsFor(peerId, "revoked")
        subscriptions.deleteAllFor(peerId)
        sessions.active().filter { it.peerId == peerId }.forEach { s -> if (s.kind == "call") runCatching { calls.endCall(s.sessionId) } }
        audit.append(AuditEntry(ts = Ids.now(), correlationId = null, peerId = peerId, kind = AuditKind.ADMIN, capability = "revoke", success = true))
      }
    }
    peers.knownCapabilities = { registry.names() }
    registerCapabilities()
    // After the hooks, so each is audited and announced as session.state like any other transition.
    runCatching { calls.failStale("process_restart") }
      .onSuccess { if (it.isNotEmpty()) android.util.Log.w("Runtime", "failed ${it.size} call session(s) left live by a previous process: $it") }
      .onFailure { android.util.Log.e("Runtime", "stale call sweep failed", it) }
    startDriverLease()
    // After registration: triggers subscribe to the event bus and schedules re-arm from the table. This runs on
    // every process start (boot included, via BootReceiver → RuntimeService → Runtime.get), which is the re-arm.
    runCatching { workflows.start() }.onFailure { android.util.Log.e("Runtime", "workflow engine failed to start", it) }
  }

  /**
   * The phone-side driver lease (see [dev.symbiosis.runtime.telephony.DriverLease]). Runs for the life of the
   * process, independent of the server: a server that is down is exactly a driver that cannot reach us.
   */
  private fun startDriverLease() {
    scope.launch {
      while (true) {
        delay(dev.symbiosis.runtime.telephony.DriverLease.CHECK_EVERY_MS)
        runCatching { checkDriverLeases(Ids.now()) }.onFailure { android.util.Log.w("Runtime", "driver lease check failed", it) }
      }
    }
  }

  /** One pass of the lease: end every peer-driven conversation whose owner has been absent for the whole lease. */
  internal fun checkDriverLeases(now: Long) {
    // Dialing can succeed even if its HTTP response is lost. Managed calls get a startup
    // deadline from session creation, independent of other sockets using the same peer token.
    for (s in sessions.active()) {
      if (s.kind != "call" || s.peerId == null || s.state.terminal) continue
      val managed = s.meta["driver_managed"] == JsonPrimitive(true)
      if (!managed) continue
      val started = s.meta["driver_attached"] == JsonPrimitive(true) ||
        dev.symbiosis.runtime.telephony.Conversations.entries().any { it.sessionId == s.sessionId }
      if (!dev.symbiosis.runtime.telephony.DriverLease.startupExpired(now, s.createdAt, managed, started)) continue
      val ended = runCatching { calls.endCall(s.sessionId) }.getOrDefault(false)
      audit.append(AuditEntry(ts = now, correlationId = s.correlationId, peerId = s.peerId,
        kind = AuditKind.ADMIN, capability = "driver_start_timeout", success = ended,
        args = buildJsonObject { put("session_id", s.sessionId) }))
    }
    for (e in dev.symbiosis.runtime.telephony.Conversations.entries()) {
      val owner = e.ownerPeerId ?: continue
      val s = sessions.get(e.sessionId) ?: continue
      // Only calls a peer placed and drives. A session with no owning peer is not the lease's to end.
      if (s.kind != "call" || s.peerId == null || s.state.terminal) continue
      val present = server?.hasSocketsFor(owner) == true || (requestsInFlight[owner]?.get() ?: 0) > 0
      val lastSeen = peers.lastActivity(owner)
      if (!dev.symbiosis.runtime.telephony.DriverLease.expired(now, e.startedAt, lastSeen, present)) continue
      val idleMs = now - maxOf(e.startedAt, lastSeen ?: Long.MIN_VALUE)
      android.util.Log.w("Runtime", "driver lease expired for ${e.sessionId} (owner $owner idle ${idleMs}ms): ending the call")
      dev.symbiosis.runtime.telephony.Conversations.stop(e.sessionId, "driver_lost")
      broker.emit(
        Topics.CALL_CONVERSATION,
        mapOf("session_id" to e.sessionId, "state" to "stopped", "reason" to "driver_lost", "owner" to owner, "idle_ms" to idleMs),
        s.correlationId,
      )
      val ended = runCatching { calls.endCall(e.sessionId) }.getOrDefault(false)
      audit.append(AuditEntry(ts = now, correlationId = s.correlationId, peerId = owner, kind = AuditKind.ADMIN, capability = "driver_lost", success = ended, args = buildJsonObject { put("session_id", e.sessionId); put("idle_ms", idleMs); put("lease_ms", dev.symbiosis.runtime.telephony.DriverLease.LEASE_MS) }))
    }
  }

  private fun registerCapabilities() {
    io.droidmcp.notification.NotificationListenerHolder.set(android.content.ComponentName(context, NotificationEventListener::class.java))
    val droid = buildList {
      addAll(io.droidmcp.sms.SmsTools.all(context))
      addAll(io.droidmcp.telephony.TelephonyTools.all(context))
      addAll(io.droidmcp.calllog.CallLogTools.all(context))
      addAll(io.droidmcp.contacts.ContactsTools.all(context))
      addAll(io.droidmcp.device.DeviceTools.all(context))
      addAll(io.droidmcp.network.NetworkTools.all(context))
      addAll(io.droidmcp.apps.AppsTools.all(context))
      addAll(io.droidmcp.intent.IntentTools.all(context))
      addAll(io.droidmcp.location.LocationTools.all(context))
      addAll(io.droidmcp.notificationsreply.NotificationsReplyTools.all(context))
      addAll(io.droidmcp.accessibility.AccessibilityTools.all(context))
      addAll(io.droidmcp.screenshot.ScreenshotTools.all(context))
      addAll(io.droidmcp.tts.TtsTools.all(context))
      addAll(io.droidmcp.audio.AudioTools.all(context))
    }
    registry.registerAll(DroidMcpAdapter.adapt(context, droid))
    registry.registerAll(NativeCapabilities.telephony(calls, sessions))
    registry.registerAll(NativeCapabilities.events(broker, subscriptions))
    registry.registerAll(NativeCapabilities.device(context) { status() })
    // Conversation mode is part of the release surface (live-verified on the Pixel, Sept 2026).
    registry.register(dev.symbiosis.runtime.capabilities.CallSpeech.speakCapability(context, broker, sessions))
    registry.register(dev.symbiosis.runtime.capabilities.CallSpeech.stopSpeakingCapability(sessions))
    registry.register(dev.symbiosis.runtime.capabilities.CallSpeech.converseCapability(context, broker, sessions))
    registry.register(dev.symbiosis.runtime.capabilities.CallAudioPlay.capability(context, sessions))
    registry.register(dev.symbiosis.runtime.capabilities.CallTurnAudio.capability(sessions))
    registry.registerAll(dev.symbiosis.runtime.capabilities.DeviceExtras.all(context))
    registry.registerAll(dev.symbiosis.runtime.capabilities.Diagnostics.all(context))
    registry.registerAll(dev.symbiosis.runtime.capabilities.Clipboard.all(context))
    registry.registerAll(dev.symbiosis.runtime.capabilities.UiGestures.all())
    // Batches dispatch each step through dispatch() above — the same path as a direct call — on the process scope,
    // detached per step so the batch deadline holds even when a step's capability ignores cancellation.
    registry.register(dev.symbiosis.runtime.automation.AutomationCapability.capability(automationExecutor))
    registry.registerAll(dev.symbiosis.runtime.automation.WorkflowCapabilities.all(workflows) { p, name -> registry.get(name)?.let { p.canSee(it) } == true })
    registry.register(dev.symbiosis.runtime.capabilities.AiAsk.capability { policy.config.value.ai })
    registry.register(dev.symbiosis.runtime.capabilities.Notify.capability(context))
    // Research probes and self-tests: debug builds only.
    if (BuildConfig.DEBUG) {
      registry.register(dev.symbiosis.runtime.capabilities.AudioProbe.capability(context))
      registry.register(dev.symbiosis.runtime.capabilities.AudioProbe.injectCapability(context))
      registry.register(dev.symbiosis.runtime.capabilities.AudioProbe.devicesCapability(context))
      registry.register(dev.symbiosis.runtime.capabilities.AudioProbe.injectRoutedCapability(context))
      registry.register(dev.symbiosis.runtime.capabilities.AudioProbe.injectPolicyCapability(context))
      registry.register(dev.symbiosis.runtime.capabilities.AsrSelfTest.capability(context))
      registry.register(dev.symbiosis.runtime.capabilities.TurnSelfTest.capability(context))
    }
  }

  // ---- lifecycle (called by RuntimeService) ----

  /** SHA-256 fingerprint of the self-signed TLS certificate (stable across restarts), or null before first TLS start. */
  @Volatile var tlsFingerprint: String? = null; private set

  /**
   * Start the server. Plain HTTP always binds loopback only. When [exposeLan] is true a second, TLS-only
   * connector binds 0.0.0.0:[tlsPort] with a persisted self-signed certificate whose fingerprint clients pin.
   */
  @Synchronized fun startServer(host: String = "127.0.0.1", port: Int = 8765, exposeLan: Boolean = false, tlsPort: Int = 8766): Server {
    server?.let { if (it.running) return it }
    val tls = if (exposeLan) io.droidmcp.tls.SelfSignedCert.loadOrCreate(java.io.File(context.filesDir, "symbiosis-tls.p12"), alias = "symbiosis", httpsPort = tlsPort).also { tlsFingerprint = it.certFingerprintSha256 } else null
    val s = Server(this, host, port, tls)
    // A half-started engine (port in use, TLS store unreadable) must not linger holding the other connector.
    try { s.start() } catch (t: Throwable) { runCatching { s.stop() }; throw t }
    server = s
    _serverState.value = s.url
    callState.start(callEvents)
    systemState.start()
    broker.emit(Topics.RUNTIME_STARTED, mapOf("version" to version, "url" to s.url))
    maintenanceJob?.cancel()
    maintenanceJob = scope.launch {
      while (true) {
        delay(60 * 60_000)
        runCatching { broker.prune(); sessions.pruneFrames(); approvals.prune(); peers.pruneCodes(); workflows.store.prune() }
          .onFailure { android.util.Log.w("Runtime", "maintenance failed", it) }
        // Separately, so a failure in one cannot starve the other. Retention keeps 30 days / 200k rows and
        // anchors the chain so phone://audit/verify still verifies what remains.
        runCatching { audit.prune() }.onFailure { android.util.Log.w("Runtime", "audit retention failed", it) }
      }
    }
    return s
  }

  @Synchronized fun stopServer(reason: String) {
    broker.emit(Topics.RUNTIME_STOPPING, mapOf("reason" to reason))
    maintenanceJob?.cancel(); maintenanceJob = null
    server?.closeAllSockets("shutdown")
    server?.stop()
    server = null
    _serverState.value = null
    callState.stop()
    systemState.stop()
  }

  /** Re-arm event sources after the user grants permissions (READ_PHONE_STATE arriving late). */
  fun rearmSources() { if (server != null) runCatching { callState.stop(); callState.start(callEvents) } }

  fun serverStats(): Map<String, Any?> = server?.stats() ?: mapOf("running" to false)

  // ---- the pipeline ----

  suspend fun invoke(principal: Principal, cap: Capability, args: Map<String, Any?>, ctx: CallContext): JsonObject {
    val t0 = Ids.now()
    val argsJson = Json.toJson(args)
    val flightKey = ctx.idempotencyKey?.let { "${ctx.peerId}|$it" }

    val digest = if (flightKey != null) audit.argsDigest(argsJson) else null
    // Claim the key before reading its stored outcome. Otherwise another invocation can finish between
    // our cache miss and acquiring the key, allowing a completed side effect to run a second time.
    if (flightKey != null && !inFlight.add(flightKey)) {
      return toolResultJson(CapabilityResult.fail("conflict.in_progress", "a call with this idempotency key is still executing"), ctx, 0)
    }
    val peerCount = ctx.peerId?.let { requestsInFlight.computeIfAbsent(it) { java.util.concurrent.atomic.AtomicInteger() } }
    peerCount?.incrementAndGet()
    try {
      // Idempotency: replay a stored successful outcome for this peer; refuse a concurrent duplicate.
      if (flightKey != null) {
        audit.findOutcome(ctx.idempotencyKey!!, ctx.peerId)?.let { stored ->
          fun refuse(code: String, detail: String, extra: Map<String, Any?> = emptyMap()): JsonObject {
            audit.append(AuditEntry(ts = t0, correlationId = ctx.correlationId, peerId = ctx.peerId, kind = AuditKind.TOOL_OUTCOME, capability = cap.name, actionClass = cap.actionClass.name, args = buildJsonObject { put("replay_refused", code); put("stored_audit_id", stored.auditId) }, success = false, errorCode = code, durationMs = 0))
            return toolResultJson(CapabilityResult.fail(code, detail, extra + mapOf("audit_id" to stored.auditId)), ctx, 0)
          }
          when (replayRefusal(stored, cap.name, digest!!)) {
            "conflict.idempotency_mismatch" -> return refuse("conflict.idempotency_mismatch", "idempotency key already used for a different call (${stored.capability ?: "unknown"}); use a new key")
            "idempotency.result_unavailable" -> return refuse("idempotency.result_unavailable", "this call already ran successfully, but its result was too large to keep for replay; it will not be run again")
          }
          audit.append(AuditEntry(ts = t0, correlationId = ctx.correlationId, peerId = ctx.peerId, kind = AuditKind.TOOL_OUTCOME, capability = cap.name, actionClass = cap.actionClass.name, args = buildJsonObject { put("replayed", true) }, success = true, idempotencyKey = ctx.idempotencyKey, durationMs = 0))
          return withMeta(stored.result as JsonObject, ctx, replayed = true)
        }
      }
      val intent = audit.append(AuditEntry(ts = t0, correlationId = ctx.correlationId, peerId = ctx.peerId, kind = AuditKind.TOOL_INTENT, capability = cap.name, actionClass = cap.actionClass.name, args = argsJson, idempotencyKey = ctx.idempotencyKey))

      val outcome = policy.evaluate(cap, args)
      audit.append(AuditEntry(ts = Ids.now(), correlationId = ctx.correlationId, peerId = ctx.peerId, kind = AuditKind.POLICY_DECISION, capability = cap.name, actionClass = cap.actionClass.name, result = JsonPrimitive(outcome::class.simpleName), success = outcome !is PolicyOutcome.Deny, errorCode = (outcome as? PolicyOutcome.Deny)?.code))

      val result: CapabilityResult = when (outcome) {
        is PolicyOutcome.Deny -> CapabilityResult.fail(outcome.code, outcome.message, outcome.extra)
        is PolicyOutcome.Approve -> approveThenMaybeExecute(cap, args, ctx, outcome)
        is PolicyOutcome.Allow -> executeAllowed(cap, args, ctx, outcome)
      }

      val body = toolResultJson(result, ctx, Ids.now() - t0, intent.id)
      audit.append(AuditEntry(ts = Ids.now(), correlationId = ctx.correlationId, peerId = ctx.peerId, kind = AuditKind.TOOL_OUTCOME, capability = cap.name, actionClass = cap.actionClass.name, args = digest?.let { d -> buildJsonObject { put(AuditStore.ARGS_DIGEST, d) } }, result = body, success = result.ok, errorCode = result.errorCode, durationMs = Ids.now() - t0, idempotencyKey = ctx.idempotencyKey))
      return body
    } finally {
      flightKey?.let { inFlight.remove(it) }
      peerCount?.decrementAndGet()
      // A request that just finished is the owner's latest sign of life, however long it ran.
      ctx.peerId?.let { peers.touch(it) }
    }
  }

  /** Approval path: create the pending action (waiter registered first), wait, and on approval re-run policy before executing. */
  private suspend fun approveThenMaybeExecute(cap: Capability, args: Map<String, Any?>, ctx: CallContext, outcome: PolicyOutcome.Approve): CapabilityResult {
    if (!approvals.notificationsAvailable()) {
      return CapabilityResult.fail("policy.approval_unavailable", "approval needs POST_NOTIFICATIONS on the phone; grant it in the Android Agent Harness app", mapOf("action_class" to cap.actionClass.name))
    }
    val cfg = policy.config.value
    val wait = (ctx.approvalWaitMs ?: cfg.approvalWaitMs).coerceIn(0, cfg.approvalMaxWaitMs)
    val managedDial = cap.name == "telephony.place_call" && args["driver_managed"] == true
    val (pa, decision) = approvals.createAndAwait(cap.name, cap.actionClass, args, ctx.peerId, ctx.correlationId, cfg.pendingTtlMs, outcome.reason, wait, allowLate = !managedDial) { created ->
      // Announce immediately (before waiting) so a watcher can approve within the window.
      broker.emit(Topics.APPROVAL_REQUIRED, mapOf("pending_action_id" to created.id, "capability" to cap.name, "action_class" to cap.actionClass.name, "expires_at" to created.expiresAt, "reason" to outcome.reason), ctx.correlationId)
    }
    return when (decision) {
      true -> {
        // The human said yes; the world may have changed while we waited (e-stop, revocation, quota), so decide again.
        when (val again = policy.evaluate(cap, args, approved = true)) {
          is PolicyOutcome.Deny -> CapabilityResult.fail(again.code, again.message, again.extra + mapOf("pending_action_id" to pa.id))
          is PolicyOutcome.Approve -> CapabilityResult.fail("policy.denied", "approval conditions changed; ask again", mapOf("pending_action_id" to pa.id))
          is PolicyOutcome.Allow -> executeAllowed(cap, args, ctx, again).also { approvals.markExecuted(pa.id, it.data + mapOf("ok" to it.ok, "error" to it.errorCode)) }
        }
      }
      false -> CapabilityResult.fail("policy.denied", "denied by user", mapOf("pending_action_id" to pa.id))
      null -> if (managedDial) CapabilityResult.fail("policy.denied", "driver approval window ended; request cancelled", mapOf("pending_action_id" to pa.id))
        else CapabilityResult.fail("policy.approval_required", "approval required: ${outcome.reason}", mapOf("pending_action_id" to pa.id, "expires_at" to pa.expiresAt, "action_class" to cap.actionClass.name))
    }
  }

  private suspend fun executeAllowed(cap: Capability, args: Map<String, Any?>, ctx: CallContext, allow: PolicyOutcome.Allow): CapabilityResult {
    // Approval may have waited for minutes. Refresh authority on both immediate and late execution paths,
    // including expiry (which has no revocation callback), before using this app's Android permissions.
    val current = ctx.peerId?.let { principalOf(it) }
    if (current == null || !current.canSee(cap)) {
      policy.release(allow)
      return CapabilityResult.fail("policy.denied", "peer no longer authorised for ${cap.name}")
    }
    val missing = cap.requiredPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) { policy.release(allow); return CapabilityResult.missingPermission(*missing.toTypedArray()) }
    return try {
      withContext(Dispatchers.IO) { cap.execute(args, ctx.copy(principal = current)) }.also { if (!it.ok) policy.release(allow) }
    } catch (t: Throwable) {
      android.util.Log.e("Runtime", "capability ${cap.name} threw", t)
      policy.release(allow)
      CapabilityResult.fail("capability.failed", t.message ?: t.javaClass.simpleName)
    }
  }

  /**
   * Late approval (the caller stopped waiting): re-authenticate the peer, re-check scope, re-run policy,
   * then execute with the original correlation id and record the outcome.
   */
  private suspend fun executeApproved(pa: PendingAction) {
    val cap = registry.get(pa.capability) ?: return
    val principal = when (pa.peerId) {
      null -> null
      PeerStore.ADMIN_ID -> peers.adminPrincipal
      else -> peers.principalFor(pa.peerId)
    }
    val corr = pa.correlationId ?: Ids.correlation()
    fun record(r: CapabilityResult) {
      approvals.markExecuted(pa.id, r.data + mapOf("ok" to r.ok, "error" to r.errorCode))
      audit.append(AuditEntry(ts = Ids.now(), correlationId = corr, peerId = pa.peerId, kind = AuditKind.TOOL_OUTCOME, capability = cap.name, actionClass = cap.actionClass.name, result = toolResultJson(r, CallContext(pa.peerId, corr), 0), success = r.ok, errorCode = r.errorCode, args = buildJsonObject { put("pending_action_id", pa.id); put("late_approval", true) }))
    }
    if (pa.capability == "telephony.place_call" && pa.args["driver_managed"] == true) {
      return record(CapabilityResult.fail("policy.denied", "managed dialing requires a live approval waiter"))
    }
    if (principal == null || !principal.canSee(cap)) return record(CapabilityResult.fail("policy.denied", "peer no longer authorised for ${cap.name}"))
    val ctx = CallContext(pa.peerId, corr, principal = principal)
    when (val again = policy.evaluate(cap, pa.args, approved = true)) {
      is PolicyOutcome.Deny -> record(CapabilityResult.fail(again.code, again.message, again.extra))
      is PolicyOutcome.Approve -> record(CapabilityResult.fail("policy.denied", "approval conditions changed; ask again"))
      is PolicyOutcome.Allow -> record(executeAllowed(cap, pa.args, ctx, again))
    }
  }

  suspend fun resolveApproval(id: String, approved: Boolean, via: String) {
    val res = approvals.resolve(id, approved) ?: return
    val pa = res.action
    audit.append(AuditEntry(ts = Ids.now(), correlationId = pa.correlationId, peerId = pa.peerId, kind = AuditKind.APPROVAL, capability = pa.capability, actionClass = pa.actionClass.name, result = JsonPrimitive(if (approved) "approved" else "denied"), args = buildJsonObject { put("pending_action_id", id); put("via", via); put("late", res.late) }, success = approved))
    broker.emit(Topics.APPROVAL_RESOLVED, mapOf("pending_action_id" to id, "decision" to if (approved) "approved" else "denied", "capability" to pa.capability, "via" to via, "late" to res.late), pa.correlationId)
    if (approved && res.late) executeApproved(pa)
  }

  private fun toolResultJson(r: CapabilityResult, ctx: CallContext, durationMs: Long, auditId: Long? = null): JsonObject {
    val structured = if (r.ok) Json.toJson(r.data) as JsonObject
    else Json.toJson(r.data + mapOf("error" to r.errorCode, "detail" to r.errorDetail)) as JsonObject
    val text = if (r.ok) Json.lenient.encodeToString(JsonElement.serializer(), structured) else "${r.errorCode}: ${r.errorDetail ?: ""}".trim()
    return buildJsonObject {
      put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
      put("structuredContent", structured)
      put("isError", !r.ok)
      put("_meta", buildJsonObject { put("correlationId", ctx.correlationId); put("durationMs", durationMs); put("auditId", auditId) })
    }
  }

  private fun withMeta(body: JsonObject, ctx: CallContext, replayed: Boolean): JsonObject = JsonObject(
    body + ("_meta" to buildJsonObject { put("correlationId", ctx.correlationId); put("replayed", replayed); put("idempotencyKey", ctx.idempotencyKey) }),
  )

  // ---- MCP surface helpers ----

  fun toolJson(cap: Capability): JsonObject = buildJsonObject {
    put("name", cap.name)
    put("description", cap.description)
    put("inputSchema", cap.inputSchema())
    put("annotations", buildJsonObject { put("readOnlyHint", cap.readOnly); put("destructiveHint", cap.actionClass >= ActionClass.DEVICE_MUTATION); put("idempotentHint", cap.idempotent); put("openWorldHint", cap.actionClass == ActionClass.COMMUNICATION || cap.actionClass == ActionClass.EXTERNAL_COMMITMENT); put("title", cap.name) })
    put("_meta", buildJsonObject { put("symbiosis", buildJsonObject { put("action_class", cap.actionClass.name); put("tier", cap.tier); put("rate_class", cap.rateClass.name.lowercase()); put("emits_events", JsonArray(cap.emitsEvents.map { JsonPrimitive(it) })); put("source", cap.source); put("version", cap.version) }) })
  }

  fun resources(): List<Pair<String, String>> = listOf(
    "phone://device/status" to "Runtime status, permissions, connectivity, active sessions (needs a device.* scope)",
    "phone://capabilities" to "Capabilities visible to this peer, with metadata",
    "phone://audit/recent" to "Last 100 audit entries (this peer's; all for admin)",
    "phone://policy" to "Effective policy configuration and overrides (admin)",
    "phone://sessions" to "Recent sessions owned by this peer",
    "phone://events/recent" to "Last 50 events in the journal that this peer's scopes cover (needs events.*)",
    "phone://audit/verify" to "Walk the audit hash chain (admin)",
  )

  /** Resources are scope-gated like tools: null means "not visible to this peer" and surfaces as unknown resource. */
  fun readResource(principal: Principal, uri: String): JsonElement? = when (uri) {
    "phone://device/status" -> if (principal.isAdmin || principal.canSubscribe("battery.changed")) Json.toJson(status()) else null
    "phone://capabilities" -> JsonArray(registry.visibleTo(principal).map { toolJson(it) })
    "phone://audit/recent" -> JsonArray(audit.recent(100, peerId = if (principal.isAdmin) null else principal.peerId).map { Json.lenient.encodeToJsonElement(AuditEntry.serializer(), it) })
    "phone://policy" -> if (!principal.isAdmin) null else buildJsonObject { put("config", Json.lenient.encodeToJsonElement(dev.symbiosis.runtime.policy.PolicyConfig.serializer(), policy.config.value)); put("overrides", Json.toJson(policy.overrides().mapValues { it.value.name })); put("emergency_stop", policy.emergencyStop.value); put("observe_only", policy.observeOnly.value) }
    "phone://sessions" -> JsonArray(sessions.recent(20).filter { principal.isAdmin || (it.peerId != null && it.peerId == principal.peerId) }.map { sessions.toJson(it) })
    "phone://events/recent" -> if (!principal.isAdmin && !principal.canSubscribe("policy.x")) null
      else JsonArray(broker.recent(50).filter { principal.canSeeTopic(it.topic) }.map { Json.lenient.encodeToJsonElement(dev.symbiosis.runtime.events.Event.serializer(), it) })
    "phone://audit/verify" -> if (!principal.isAdmin) null else {
      val broken = audit.verifyChain(); val anchor = audit.anchor()
      buildJsonObject { put("intact", broken == null); put("first_broken_id", broken); put("entries", audit.count()); put("verified_from_id", anchor?.firstKeptId); put("pruned_through", anchor?.createdAt) }
    }
    else -> null
  }

  fun permissionStatus(): Map<String, Boolean> = listOf(
    "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
    "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG", "android.permission.CALL_PHONE",
    "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS", "android.permission.ANSWER_PHONE_CALLS",
    "android.permission.ACCESS_FINE_LOCATION", "android.permission.POST_NOTIFICATIONS",
  ).associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

  fun status(): Map<String, Any?> = mapOf(
    "device_id" to deviceId,
    "version" to version,
    "android" to mapOf("release" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT, "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER),
    "server" to serverStats(),
    "tls_fingerprint_sha256" to tlsFingerprint,
    "uptime_ms" to (Ids.now() - startedAt),
    "capabilities" to registry.size(),
    "permissions" to permissionStatus(),
    "notification_listener_connected" to NotificationEventListener.connected,
    "system" to systemState.snapshot(),
    "events" to mapOf("count" to broker.count(), "last_id" to broker.lastId()),
    "audit_entries" to audit.count(),
    "secure_storage" to if (secure.usingKeystore) "keystore" else "FALLBACK_PLAINTEXT_PREFS",
    "active_sessions" to sessions.active().map { it.sessionId },
    "pending_approvals" to approvals.pending().size,
    "policy" to mapOf("emergency_stop" to policy.emergencyStop.value, "observe_only" to policy.observeOnly.value),
    "peers" to peers.peers().count { it.revokedAt == null },
  )

  companion object {
    /**
     * Whether a stored outcome may be replayed for this call: null = replay it, else the refusal code.
     *  - A key is only a claim about WHICH call this is. Replay only if the stored outcome is for this very tool and
     *    these very arguments; otherwise an edited request retried under an old key (an edited batch step, a client
     *    bug) would get another call's result back and believe it had run. Rows from before replay was bound to
     *    args carry no digest and are refused the same way.
     *  - An outcome stored cut down to 8 KB is not the real result. Re-executing would repeat a side effect that
     *    already happened (the call SUCCEEDED; only its result is lost), so the retry is refused instead, with the
     *    audit id of the row that records it ran. Refusing is the choice that is safe for non-idempotent tools.
     */
    fun replayRefusal(stored: AuditStore.StoredOutcome, capability: String, argsDigest: String): String? = when {
      stored.capability != capability || stored.argsDigest == null || stored.argsDigest != argsDigest -> "conflict.idempotency_mismatch"
      stored.truncated -> "idempotency.result_unavailable"
      else -> null
    }

    /**
     * Which emitted events are audited. Side-effect topics only (battery/connectivity chatter would drown the log),
     * and not interim transcripts: a live call streams several `call.transcript {final:false}` per second, each
     * superseded by the next, and they were most of the audit log's growth. Finals and state changes stay.
     */
    fun auditsEvent(topic: String, payload: JsonObject): Boolean {
      if (!(topic.startsWith("messaging.") || topic.startsWith("call.") || topic.startsWith("policy.") || topic.startsWith("session."))) return false
      if (topic == Topics.CALL_TRANSCRIPT && (payload["final"] as? JsonPrimitive)?.content == "false") return false
      return true
    }

    @Volatile private var instance: Runtime? = null
    fun get(context: Context): Runtime = instance ?: synchronized(this) { instance ?: Runtime(context.applicationContext).also { instance = it } }
  }
}
