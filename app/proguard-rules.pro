# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# Room Database rules
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Kotlinx Serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Room entities and session envelopes are serialized with Gson/reflection. Keep wire names
# identical in minified releases, including the dynamic media/reference mapper.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.example.data.** { *; }
-keep class com.example.util.Supabase$Session { *; }
-keep class com.example.util.Supabase$UserInfo { *; }
-keep class com.example.ui.CloudBackupRecord { *; }
