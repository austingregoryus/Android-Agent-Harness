package dev.symbiosis.runtime.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.core.RuntimeService
import dev.symbiosis.runtime.policy.ActionClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One-screen control panel: status, pairing, permissions, policy switches, approvals, peers, audit. */
class MainActivity : ComponentActivity() {
  private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Runtime.get(this).rearmSources() }

  private val requestRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    android.util.Log.i("SymbiosisDebug", "SYMBIOSIS_DIALER_ROLE result=${r.resultCode} holder=${isDefaultDialer()}")
  }

  fun isDefaultDialer(): Boolean = getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)

  /** Phase C research: ask the user to make Android Agent Harness the default dialer (reversible in Settings > Default apps). */
  private fun requestDialerRole() {
    val rm = getSystemService(android.app.role.RoleManager::class.java)
    if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) { android.util.Log.w("SymbiosisDebug", "SYMBIOSIS_DIALER_ROLE unavailable"); return }
    if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) { android.util.Log.i("SymbiosisDebug", "SYMBIOSIS_DIALER_ROLE already held"); return }
    requestRole.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.getBooleanExtra(EXTRA_REQUEST_DIALER_ROLE, false)) requestDialerRole()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val runtime = Runtime.get(this)
    if (RuntimeService.isEnabled(this)) RuntimeService.start(this)
    if (intent?.getBooleanExtra(EXTRA_REQUEST_DIALER_ROLE, false) == true) requestDialerRole()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { Screen(runtime) }
      }
    }
  }

  @Composable
  private fun Screen(runtime: Runtime) {
    val serverUrl by runtime.serverState.collectAsState()
    val estop by runtime.policy.emergencyStop.collectAsState()
    val observe by runtime.policy.observeOnly.collectAsState()
    val policyCfg by runtime.policy.config.collectAsState()
    val asksFirst = policyCfg.classDefaults[dev.symbiosis.runtime.policy.ActionClass.EXTERNAL_COMMITMENT] ==
      dev.symbiosis.runtime.policy.PolicyDecision.APPROVE
    var tick by remember { mutableStateOf(0) }
    var showToken by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var chainState by remember { mutableStateOf("not checked") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // All SQLite reads happen on IO; the UI only renders the latest snapshot.
    data class Snapshot(val status: Map<String, Any?>, val perms: Map<String, Boolean>, val pending: List<dev.symbiosis.runtime.policy.PendingAction>, val peers: List<dev.symbiosis.runtime.transport.Peer>, val audit: List<dev.symbiosis.runtime.audit.AuditEntry>, val workflows: List<dev.symbiosis.runtime.automation.Workflow>)
    val snapshot by androidx.compose.runtime.produceState<Snapshot?>(initialValue = null, tick, serverUrl) {
      value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Snapshot(runtime.status(), runtime.permissionStatus(), runtime.approvals.pending(), runtime.peers.peers(), runtime.audit.recent(15), runtime.workflows.store.list())
      }
    }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val status = snapshot?.status ?: emptyMap()
    val perms = snapshot?.perms ?: emptyMap()
    val pending = snapshot?.pending ?: emptyList()
    val peers = snapshot?.peers ?: emptyList()
    val audit = snapshot?.audit ?: emptyList()
    val workflows = snapshot?.workflows ?: emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Android Agent Harness", style = MaterialTheme.typography.headlineMedium)
      Text("Device ${runtime.deviceId} · v${runtime.version} · ${status["capabilities"]} capabilities", style = MaterialTheme.typography.bodySmall)

      Section("Runtime") {
        Text(if (serverUrl != null) "Listening at $serverUrl" else "Stopped")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { RuntimeService.start(this@MainActivity) }) { Text("Start") }
          OutlinedButton(onClick = { startService(Intent(this@MainActivity, RuntimeService::class.java).setAction(RuntimeService.ACTION_STOP)) }) { Text("Stop") }
        }
        Text("From a workstation: adb forward tcp:8765 tcp:8765, then POST http://127.0.0.1:8765/mcp", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide admin token" else "Show admin token") }
        }
        if (showToken) Text(runtime.secure.adminToken, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        var lan by remember { mutableStateOf(RuntimeService.isLanExposed(this@MainActivity)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Expose on network (TLS, port ${RuntimeService.DEFAULT_TLS_PORT})")
          Switch(checked = lan, onCheckedChange = { on -> lan = on; RuntimeService.setExposeLan(this@MainActivity, on) })
        }
        runtime.tlsFingerprint?.let { Text("Pin this certificate fingerprint on the gateway:\n$it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text("Plain HTTP stays loopback-only. Agents on the LAN/Tailscale must use https://<phone-ip>:${RuntimeService.DEFAULT_TLS_PORT} and pin the fingerprint.", style = MaterialTheme.typography.bodySmall)
      }

      Section("Safety") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(if (estop) "EMERGENCY STOP engaged" else "Emergency stop")
          Switch(checked = estop, onCheckedChange = { runtime.policy.setEmergencyStop(it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Observe-only (read tools only)")
          Switch(checked = observe, onCheckedChange = { runtime.policy.setObserveOnly(it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Ask me first: texts, calls, screen control")
          Switch(checked = asksFirst, onCheckedChange = { runtime.policy.setAsksFirst(it) })
        }
        Text(
          if (asksFirst) "Each one waits for your Approve tap here or in a notification."
          else "Agents do these without asking. Rate limits, the audit log and the two switches above still apply.",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      Section("Pair an agent") {
        Text("Mint a one-time code (5 min). The agent POSTs it to /pair and receives a scoped token.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            pairingCode = runtime.peers.mintStandardCode().code
          }) { Text("Standard agent") }
          OutlinedButton(onClick = { pairingCode = runtime.peers.mintPairingCode(listOf("*"), ActionClass.DEVICE_MUTATION).code }) { Text("Full access") }
        }
        pairingCode?.let { Text(it, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace) }
      }

      Section("Permissions") {
        val missing = perms.filterValues { !it }.keys
        perms.forEach { (p, ok) -> Text("${if (ok) "✓" else "✗"} ${p.removePrefix("android.permission.")}", style = MaterialTheme.typography.bodySmall) }
        Text("${if (dev.symbiosis.runtime.events.sources.NotificationEventListener.connected) "✓" else "✗"} Notification listener", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(enabled = missing.isNotEmpty(), onClick = { requestPerms.launch(missing.toTypedArray()) }) { Text("Grant missing") }
          OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Listener") }
          OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }) { Text("Battery") }
        }
      }

      if (pending.isNotEmpty()) Section("Pending approvals (${pending.size})") {
        pending.forEach { pa ->
          Text("${pa.capability} · ${pa.actionClass} · ${pa.args.entries.take(2).joinToString { (k, v) -> "$k=$v" }}", style = MaterialTheme.typography.bodySmall)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { runtime.resolveApproval(pa.id, true, "ui"); tick++ } }) { Text("Approve") }
            OutlinedButton(onClick = { scope.launch { runtime.resolveApproval(pa.id, false, "ui"); tick++ } }) { Text("Deny") }
          }
        }
      }

      Section("Paired agents (${peers.count { it.revokedAt == null }})") {
        peers.forEach { p ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${p.label} · ${p.maxActionClass} · ${p.scopes.joinToString(",")}${if (p.revokedAt != null) " (revoked)" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (p.revokedAt == null) OutlinedButton(onClick = { runtime.peers.revoke(p.peerId); tick++ }) { Text("Revoke") }
          }
        }
      }

      Section("Workflows (${workflows.count { it.enabled }} of ${workflows.size} on)") {
        Text("Saved by paired agents. A triggered workflow runs by itself as the agent that saved it, with that agent's permissions — no approval tap. Emergency stop turns them all off.", style = MaterialTheme.typography.bodySmall)
        if (workflows.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
        val peerLabels = peers.associate { it.peerId to it.label }
        workflows.forEach { wf ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text(wf.name, style = MaterialTheme.typography.bodyMedium)
              val trig = wf.triggerSpec?.let { dev.symbiosis.runtime.automation.Triggers.describe(it) } ?: "manual only"
              val owner = if (wf.owner == dev.symbiosis.runtime.transport.PeerStore.ADMIN_ID) "admin" else peerLabels[wf.owner] ?: wf.owner
              Text("$trig · by $owner", style = MaterialTheme.typography.bodySmall)
              val last = wf.lastRunAt?.let { t ->
                val ok = (wf.lastResult?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                val ago = ((System.currentTimeMillis() - t) / 60_000).let { if (it < 1) "just now" else if (it < 120) "$it min ago" else "${it / 60} h ago" }
                "last run $ago · ${if (ok) "ok" else "failed"} · ${wf.runCount} runs"
              } ?: "never run"
              Text(last + (wf.disabledReason?.let { " · off: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = wf.enabled, onCheckedChange = { on ->
              scope.launch {
                // Same rule as automation.enable: a revoked or expired owner's workflow cannot be switched back on.
                val refused = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                  if (on && runtime.workflows.ownerUnavailable(wf.owner)) true
                  else { runtime.workflows.store.setEnabled(wf.id, on, if (on) null else "disabled_in_app"); runtime.workflows.reload(); false }
                }
                if (refused) android.widget.Toast.makeText(this@MainActivity, "Its owner is revoked or expired; it cannot be turned on.", android.widget.Toast.LENGTH_LONG).show()
                tick++
              }
            })
          }
        }
        if (workflows.any { it.enabled }) OutlinedButton(onClick = {
          scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.workflows.disableAll("disabled_in_app") }; tick++ }
        }) { Text("Disable all") }
      }

      Section("Audit (${status["audit_entries"]} entries, chain $chainState)") {
        OutlinedButton(onClick = {
          chainState = "checking…"
          scope.launch { chainState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.audit.verifyChain()?.let { "BROKEN at #$it" } ?: "intact" } }
        }) { Text("Verify chain") }
        audit.forEach { e ->
          Text("${e.kind} ${e.capability ?: ""} ${if (e.success == false) "✗ ${e.errorCode}" else ""}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }

  companion object { const val EXTRA_REQUEST_DIALER_ROLE = "request_dialer_role" }

  @Composable
  private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
      }
    }
  }
}
