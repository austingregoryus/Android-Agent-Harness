# Android Agent Harness runtime — R8 rules for the release build.

# Keep line numbers for readable crash reports (names are still obfuscated).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep serializers and @Serializable classes' companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.symbiosis.runtime.**$$serializer { *; }
-keepclassmembers class dev.symbiosis.runtime.** { *** Companion; }
-keepclasseswithmembers class dev.symbiosis.runtime.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor + Netty: engines are discovered reflectively and Netty uses unsafe/reflection extensively.
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log4j.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.jetbrains.kotlin.**

# ONNX Runtime (smart-turn end-of-turn model): its JNI code looks classes and methods up by name
# (OnnxTensor, TensorInfo, ...). Renamed or stripped, the first model run aborts the process with
# "JNI DETECTED ERROR: java_class == null in GetMethodID" - release-only, found on a live call 2026-09-23.
-keep class ai.onnxruntime.** { *; }

# droid-mcp tool modules build tool lists reflectively in places; keep them intact.
-keep class io.droidmcp.** { *; }

# BouncyCastle (self-signed certificate builder used by droid-mcp-tls).
-keep class org.bouncycastle.** { *; }

# Android components referenced from the manifest are kept by AGP; keep our receivers' actions readable.
-keep class dev.symbiosis.runtime.core.DebugReceiver { *; }
-keep class dev.symbiosis.runtime.policy.ApprovalReceiver { *; }

# Annotation-only classes referenced by Tink (security-crypto); absent at runtime by design.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
