package dev.symbiosis.runtime.ui

import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.symbiosis.runtime.telephony.SymbiosisInCallService
import kotlinx.coroutines.delay

/**
 * Minimal in-call screen. Only reachable while this app is the default dialer (Phase C research): Android
 * expects the dialer to draw the call UI, so incoming calls need somewhere to be answered or rejected.
 */
class InCallActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true); setTurnScreenOn(true)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
          var tick by remember { mutableStateOf(0) }
          LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
          val calls = remember(tick) { SymbiosisInCallService.Holder.calls.keys.toList() }
          Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Android Agent Harness call", style = MaterialTheme.typography.headlineMedium)
            if (calls.isEmpty()) { Text("No call"); LaunchedEffect(tick) { if (tick > 4) finish() } }
            calls.forEach { c ->
              val number = c.details.handle?.schemeSpecificPart ?: "unknown"
              Text("$number · ${SymbiosisInCallService.stateName(c.state)}", style = MaterialTheme.typography.titleLarge)
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.state == Call.STATE_RINGING) Button(onClick = { c.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) }) { Text("Answer") }
                if (c.state == Call.STATE_RINGING) OutlinedButton(onClick = { c.reject(false, null) }) { Text("Reject") }
                if (c.state != Call.STATE_RINGING && c.state != Call.STATE_DISCONNECTED) OutlinedButton(onClick = { c.disconnect() }) { Text("Hang up") }
                if (c.state == Call.STATE_ACTIVE) OutlinedButton(onClick = { SymbiosisInCallService.Holder.instance?.setAudioRoute(android.telecom.CallAudioState.ROUTE_SPEAKER) }) { Text("Speaker") }
              }
            }
          }
        }
      }
    }
  }
}
