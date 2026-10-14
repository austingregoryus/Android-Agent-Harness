package dev.symbiosis.runtime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Handles ACTION_DIAL / tel: links, which the default-dialer role requires an app to declare. It does not
 * dial anything itself: agents place calls through telephony.place_call, and a human uses the system UI.
 */
class DialActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val number = intent?.data?.schemeSpecificPart
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.padding(24.dp)) {
            Text("Android Agent Harness is the default dialer", style = MaterialTheme.typography.headlineSmall)
            Text(if (number != null) "Dial request for $number ignored: calls are placed by paired agents via telephony.place_call." else "Calls are placed by paired agents. Hand the dialer role back to Phone in Settings > Apps > Default apps to dial by hand.")
          }
        }
      }
    }
  }
}
