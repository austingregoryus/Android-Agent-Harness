package dev.symbiosis.runtime.events.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.Topics

/** Battery and connectivity → events. Battery emits only on percent/charging changes to avoid spam. */
class SystemStateSource(private val context: Context, private val broker: EventBroker) {
  private var lastPercent = -1
  private var lastCharging: Boolean? = null
  private var lastTransport: String? = null
  private var lastInternet: Boolean? = null

  private val batteryReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
      val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
      val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      if (pct != lastPercent || charging != lastCharging) {
        lastPercent = pct; lastCharging = charging
        broker.emit(Topics.BATTERY_CHANGED, mapOf("percent" to pct, "charging" to charging, "status" to statusName(status)))
      }
    }
  }

  private val netCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(caps)
    override fun onLost(network: Network) = report(null)
  }

  fun start() {
    context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val cm = context.getSystemService(ConnectivityManager::class.java)
    cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
    report(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
  }

  fun stop() {
    runCatching { context.unregisterReceiver(batteryReceiver) }
    runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
  }

  fun snapshot(): Map<String, Any?> = mapOf("battery_percent" to lastPercent, "charging" to lastCharging, "transport" to lastTransport, "internet_available" to lastInternet)

  private fun report(caps: NetworkCapabilities?) {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val active = caps ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val internet = active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val transport = when {
      active == null -> "none"
      active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
      active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
      else -> "other"
    }
    if (transport != lastTransport || internet != lastInternet) {
      lastTransport = transport; lastInternet = internet
      broker.emit(Topics.CONNECTIVITY_CHANGED, mapOf("internet_available" to internet, "transport" to transport))
    }
  }

  private fun statusName(s: Int) = when (s) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "full"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"; else -> "unknown"
  }
}
