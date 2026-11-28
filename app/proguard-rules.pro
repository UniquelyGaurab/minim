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

# Keep RoomDatabase subclass constructors (room-runtime 2.5.0 omits { <init>(); } and AGP 9 R8
# strips WorkDatabase_Impl.<init>(), crashing WorkManagerInitializer before Application.onCreate).
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# Keep WorkManager InputMerger constructors (work-runtime 2.9.0 omits { <init>(); }, stripping
# OverwritingInputMerger.<init>() instantiated reflectively when workers run).
-keep class * extends androidx.work.InputMerger {
    <init>();
}

