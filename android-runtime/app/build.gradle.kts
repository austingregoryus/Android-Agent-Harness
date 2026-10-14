plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "dev.symbiosis.runtime"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.symbiosis.runtime"
    minSdk = 29  // Android 10: 14 call sites already needed it (lint NewApi, 2026-09-23)
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      // R8 shrinks the ~86 MB debug APK (droid-mcp pulls ML Kit/CameraX transitively) and obfuscates.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Release artifacts are unsigned. Configure your own signing key outside the repository to distribute.
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/INDEX.LIST"
      excludes += "/META-INF/io.netty.versions.properties"
    }
  }

  lint {
    abortOnError = false
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

val ktorVersion = "3.1.2"
val droidMcpVersion = "0.10.1"

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
  implementation(composeBom)

  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
  implementation("androidx.lifecycle:lifecycle-service:2.10.0")
  implementation("androidx.activity:activity-compose:1.12.2")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

  implementation("androidx.security:security-crypto:1.1.0")

  // Transport: MCP Streamable HTTP + WebSocket event/session channels.
  implementation("io.ktor:ktor-server-core:$ktorVersion")
  // Netty engine: the only Ktor engine with TLS support on Android (CIO has none). droid-mcp runs it on Android too.
  implementation("io.ktor:ktor-server-netty:$ktorVersion")
  implementation("io.ktor:ktor-server-websockets:$ktorVersion")
  implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
  implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

  // Capability substrate (Apache-2.0). Consumed behind CapabilityAdapter.
  // io.ktor is excluded from every droid-mcp artifact: droid-mcp-core pulls Ktor Netty at runtime scope
  // and we never start its HttpTransport. Our own Ktor CIO deps above satisfy droid-mcp-core's compile refs.
  listOf(
    "droid-mcp-core", "droid-mcp-sms", "droid-mcp-telephony", "droid-mcp-calllog", "droid-mcp-contacts",
    "droid-mcp-device", "droid-mcp-network", "droid-mcp-apps", "droid-mcp-intent", "droid-mcp-location",
    "droid-mcp-notifications-reply", "droid-mcp-notification-listener", "droid-mcp-accessibility",
    "droid-mcp-screenshot", "droid-mcp-tts", "droid-mcp-audio", "droid-mcp-tls",
  ).forEach { module ->
    implementation("com.github.stixez.droid-mcp:$module:$droidMcpVersion") {
      exclude(group = "io.ktor")
    }
  }

  // Workflow `where`/`if` regexes: RE2/J matches in linear time (no backtracking), so a hostile pattern cannot pin a
  // thread. Android's java.util.regex is ICU-backed and copies the input to a String before a native match, so a
  // deadline cannot be enforced there at all. (Go/RE2 syntax: no backreferences or lookaround.)
  implementation("com.google.re2j:re2j:1.8")

  // Semantic end-of-turn detection (pipecat smart-turn v3, 8 MB int8 ONNX in assets/). The JVM artifact lets
  // the unit tests run the same model against golden features from the reference Python pipeline.
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
  testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("org.robolectric:robolectric:4.16")
  testImplementation("androidx.test:core:1.7.0")
}
