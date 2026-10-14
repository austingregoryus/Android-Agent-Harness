package dev.symbiosis.runtime.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.symbiosis.runtime.R
import dev.symbiosis.runtime.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persistent foreground service (type specialUse) that owns the server lifecycle and the runtime
 * notification with Stop / Emergency-stop / Observe-only actions. Restarts on boot via BootReceiver
 * and on process death via START_STICKY.
 */
class RuntimeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var wakeLock: PowerManager.WakeLock? = null
  private var flagsJob: Job? = null
  private var startJob: Job? = null

  /** Set once the Runtime has been built (off the main thread); the notification says "starting" until then. */
  @Volatile private var runtime: Runtime? = null
  /** Why the last start attempt failed, shown in the notification while retrying; null once running. */
  @Volatile private var startError: String? = null
  @Volatile private var nextRetryAt: Long = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForeground FIRST, before anything that can be slow or throw. Building the Runtime (database open,
    // Keystore-backed secure storage, capability registry) used to come first, and a slow Keystore on boot could
    // blow the 5 s window startForegroundService() gives, which kills the process outright.
    startForegroundCompat(buildNotification())
    when (intent?.action) {
      ACTION_STOP -> { stopRuntime("user_stop"); return START_NOT_STICKY }
      ACTION_EMERGENCY_STOP -> { withRuntime { it.policy.setEmergencyStop(true) }; return START_STICKY }
      ACTION_TOGGLE_OBSERVE -> { withRuntime { it.policy.setObserveOnly(!it.policy.observeOnly.value) }; return START_STICKY }
    }
    if (wakeLock == null) {
      wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "symbiosis:runtime").also { it.acquire() }
    }
    val host = intent?.getStringExtra(EXTRA_HOST) ?: prefs().getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
    val port = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 } ?: prefs().getInt("port", DEFAULT_PORT)
    val exposeLan = intent?.getBooleanExtra(EXTRA_EXPOSE_LAN, prefs().getBoolean("expose_lan", false)) ?: prefs().getBoolean("expose_lan", false)
    prefs().edit().putString("host", host).putInt("port", port).putBoolean("expose_lan", exposeLan).putBoolean("enabled", true).apply()
    startWithRetry(host, port, exposeLan)
    return START_STICKY
  }

  /** Run [block] against the Runtime off the main thread, then refresh the notification. */
  private fun withRuntime(block: (Runtime) -> Unit) {
    scope.launch {
      withContext(Dispatchers.IO) { runCatching { block(Runtime.get(this@RuntimeService).also { runtime = it }) } }
        .onFailure { android.util.Log.e(TAG, "runtime action failed", it) }
      refreshNotification()
    }
  }

  /**
   * Build the Runtime and start the server, retrying with backoff until it runs. A failed start used to be
   * logged and forgotten: after a reboot where the port was briefly taken (or the Keystore was not ready) the
   * phone sat in "server stopped" with the service alive and nobody told — on a phone that runs unattended, that
   * is an outage until someone opens the app. Now it keeps trying (2 s doubling to 5 min) and the notification
   * shows the error and the next attempt. A new start request supersedes a pending retry.
   */
  private fun startWithRetry(host: String, port: Int, exposeLan: Boolean) {
    startJob?.cancel()
    startJob = scope.launch {
      var backoff = RETRY_INITIAL_MS
      while (isActive) {
        val err = withContext(Dispatchers.IO) {
          runCatching {
            val rt = Runtime.get(this@RuntimeService)
            runtime = rt
            rt.startServer(host, port, exposeLan)
          }.exceptionOrNull()
        }
        watchFlags()
        if (err == null) {
          startError = null
          refreshNotification()
          return@launch
        }
        android.util.Log.e(TAG, "server start failed; retrying in ${backoff}ms", err)
        startError = (err.message ?: err.javaClass.simpleName).take(120)
        nextRetryAt = System.currentTimeMillis() + backoff
        refreshNotification()
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
      }
    }
  }

  /** Keep the notification in step with the kill switches and server state, once the Runtime exists. */
  private fun watchFlags() {
    val rt = runtime ?: return
    if (flagsJob != null) return
    flagsJob = scope.launch {
      launch { rt.policy.emergencyStop.collect { refreshNotification() } }
      launch { rt.policy.observeOnly.collect { refreshNotification() } }
      launch { rt.serverState.collect { refreshNotification() } }
    }
  }

  /** Android 15+ may time out special-use services; we restart ourselves rather than die silently. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    android.util.Log.w(TAG, "foreground service timeout (type=$fgsType)")
    startJob?.cancel(); startJob = null
    runCatching { Runtime.get(this).audit.append(dev.symbiosis.runtime.audit.AuditEntry(ts = Ids.now(), correlationId = null, peerId = null, kind = dev.symbiosis.runtime.audit.AuditKind.ADMIN, capability = "fgs_timeout", success = true)) }
    // Android 15+ requires the service to stop here; an app in the background cannot restart a foreground
    // service from an inexact alarm, so tell the user instead of throwing ForegroundServiceStartNotAllowedException.
    Runtime.get(this).stopServer("fgs_timeout")
    postPausedNotification("Android paused the runtime (foreground service time limit). Tap to restart.")
    stopSelf()
  }

  override fun onDestroy() {
    startJob?.cancel()
    flagsJob?.cancel()
    runCatching { wakeLock?.release() }
    wakeLock = null
    // START_STICKY lets the system restart us after a low-memory kill; no alarm-based restart (see onTimeout).
    super.onDestroy()
  }

  private fun postPausedNotification(text: String) {
    val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Android Agent Harness paused").setContentText(text)
      .setContentIntent(open).setAutoCancel(true).build()
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
  }

  private fun stopRuntime(reason: String) {
    startJob?.cancel(); startJob = null
    prefs().edit().putBoolean("enabled", false).apply()
    // Stopping Netty waits up to ~2 s for in-flight requests; not on the main thread.
    scope.launch(Dispatchers.IO) { runCatching { Runtime.get(this@RuntimeService).stopServer(reason) }.onFailure { android.util.Log.e(TAG, "stop failed", it) } }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun startForegroundCompat(n: Notification) {
    if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
    else startForeground(NOTIF_ID, n)
  }

  private fun refreshNotification() {
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
  }

  private fun buildNotification(): Notification {
    val runtime = this.runtime
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun svc(action: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RuntimeService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val estop = runtime?.policy?.emergencyStop?.value ?: false
    val observe = runtime?.policy?.observeOnly?.value ?: false
    val url = runtime?.serverState?.value
    val err = startError
    val state = when {
      estop -> "EMERGENCY STOP engaged"
      url == null && err != null -> "START FAILED: $err · retry in ${((nextRetryAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s"
      runtime == null -> "starting…"
      url == null -> "server stopped"
      runtime?.tlsFingerprint != null && prefs().getBoolean("expose_lan", false) -> "listening · $url + https://0.0.0.0:$DEFAULT_TLS_PORT"
      observe -> "observe-only · $url"
      else -> "listening · $url"
    }
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_compass)
      .setContentTitle("Android Agent Harness runtime")
      .setContentText(state)
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .addAction(Notification.Action.Builder(null, "Stop", svc(ACTION_STOP, 1)).build())
      .addAction(Notification.Action.Builder(null, if (estop) "E-stop ON" else "Emergency stop", svc(ACTION_EMERGENCY_STOP, 2)).build())
      .addAction(Notification.Action.Builder(null, if (observe) "Observe: ON" else "Observe only", svc(ACTION_TOGGLE_OBSERVE, 3)).build())
      .build()
  }

  private fun ensureChannel() {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.channel_runtime), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
    )
  }

  private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  companion object {
    const val TAG = "RuntimeService"
    const val CHANNEL = "symbiosis.runtime"
    const val NOTIF_ID = 1001
    const val PREFS = "symbiosis.service"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8765
    const val ACTION_STOP = "dev.symbiosis.STOP"
    const val ACTION_EMERGENCY_STOP = "dev.symbiosis.EMERGENCY_STOP"
    const val ACTION_TOGGLE_OBSERVE = "dev.symbiosis.TOGGLE_OBSERVE"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_EXPOSE_LAN = "expose_lan"
    const val DEFAULT_TLS_PORT = 8766
    private const val RETRY_INITIAL_MS = 2_000L
    private const val RETRY_MAX_MS = 5 * 60_000L

    fun start(context: Context, host: String? = null, port: Int? = null, exposeLan: Boolean? = null) {
      val i = Intent(context, RuntimeService::class.java)
      host?.let { i.putExtra(EXTRA_HOST, it) }
      port?.let { i.putExtra(EXTRA_PORT, it) }
      exposeLan?.let { i.putExtra(EXTRA_EXPOSE_LAN, it) }
      context.startForegroundService(i)
    }

    fun isLanExposed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("expose_lan", false)

    /** Restart the service with a new exposure setting (requires a server restart to rebind connectors). */
    fun setExposeLan(context: Context, on: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("expose_lan", on).apply()
      Runtime.get(context).stopServer("rebind")
      start(context, exposeLan = on)
    }

    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
  }
}
