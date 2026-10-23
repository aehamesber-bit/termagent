# ProGuard rules for DeepSeek Agent

# Keep application class
-keep class com.termagent.deepseekagent.DeepSeekAgentApplication { *; }

# Keep model classes
-keep class com.termagent.deepseekagent.core.** { *; }

# Keep UI classes
-keep class com.termagent.deepseekagent.ui.** { *; }

# Keep bridge classes
-keep class com.termagent.deepseekagent.bridge.** { *; }

# Keep service classes
-keep class com.termagent.deepseekagent.service.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Okio
-keep class okio.** { *; }
-dontwarn okio.**

# Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# AndroidX
-keep class androidx.** { *; }

# Material Components
-keep class com.google.android.material.** { *; }

# JSoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# WebView JavaScript Interface
-keepclassmembers class com.termagent.deepseekagent.bridge.DeepSeekBridge$BridgeJavaInterface {
    @android.webkit.JavascriptInterface <methods>;
}