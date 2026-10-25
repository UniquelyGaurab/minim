plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // v5.0: reads app/google-services.json (Firebase). Applied in app/ only when that file exists.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
