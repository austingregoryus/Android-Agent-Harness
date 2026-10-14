package dev.symbiosis.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.symbiosis.runtime.capabilities.SelfProtection

class RuntimeApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Counts our started activities so screen control is refused while any of them is visible (SelfProtection).
    registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
      override fun onActivityStarted(activity: Activity) { SelfProtection.startedActivities.incrementAndGet() }
      override fun onActivityStopped(activity: Activity) { SelfProtection.startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) } }
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
  }
}
