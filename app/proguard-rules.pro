# B Agent keeps relevant classes since minification is disabled for now.
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-keepattributes *Annotation*
-keep class com.bagent.app.** { *; }