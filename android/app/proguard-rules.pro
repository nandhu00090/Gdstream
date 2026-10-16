# Keep React Native &Custom Bridge Modules safe while obfuscating internals
-keep class com.gdstream.** { *; }
-keep class com.brentvatne.** { *; }
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }
-keep class androidx.media3.** { *; }
-keep class org.wonday.orientation.** { *; }

-dontwarn javax.annotation.**
-dontwarn com.google.ads.interactivemedia.**
-dontwarn androidx.media3.**
-dontwarn okio.**
