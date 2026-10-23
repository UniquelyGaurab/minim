# Keep Media3 / ExoPlayer classes instantiated via reflection
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep NewPipeExtractor and its Rhino/Json dependencies safe during R8 shrinking
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.jsoup.**

# OkHttp3 & Okio rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Keep MinimMusic data models
-keep class com.minim.music.data.model.** { *; }
