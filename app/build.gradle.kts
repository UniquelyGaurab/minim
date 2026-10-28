import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// v5.0: Firebase (Google sign-in, sync, remote config). app/google-services.json is private and never
// committed. Without it the app still builds and runs: the account features just stay hidden.
// See FIREBASE_SETUP.md and app/google-services.json.example.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Optional signing key. Put a keystore.properties file (never committed) in the project root
// with storeFile, storePassword, keyAlias and keyPassword to sign builds with your own key.
// Without it, Gradle signs debug builds with the standard Android debug key on your machine.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.minim.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.minim.music"
        minSdk = 28
        targetSdk = 34
        versionCode = 37
        versionName = "5.5.1"
        // v5.0: Minim is English only; drops the dozens of translations Firebase / Play services bring
        resourceConfigurations += listOf("en")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("minim") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("minim")?.let { signingConfig = it }
            // Shrunk with R8 to keep the APK small
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // Compress dex inside the APK to keep the download small
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // v5.0: Firestore/gRPC ship their .proto sources; the lite runtime never reads them
            excludes += "**/*.proto"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Core library desugaring required by NewPipeExtractor on Android 9 (API 28)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    // Core AndroidX & Traditional Views
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines for off-main-thread extraction, database, and bitmap work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Media3 ExoPlayer & MediaSessionService (Single authoritative playback engine)
    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // v4.9.5: NewPipeExtractor dev commit e8f8914 (22 Oct 2026), the same one NewPipe Nightly uses.
    // It has the October n-parameter fix (PR #1545 + #1556) and streams from the VISIONOS client.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:e8f89145a830b4d6cae32412007f24ea8b36c73a")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // v5.0: Firebase. 33.5.1 is the newest BoM that still builds with compileSdk 34 / Kotlin 1.9.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-config") // used from part 3 (remote vibe / switches)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    constraints {
        // NewPipeExtractor pulls protobuf-javalite 4.x, which already contains DescriptorProtos. Firestore's
        // protolite-well-known-types 18.x bundles its own copy (duplicate classes); 19.0.0 is the build made
        // for protobuf 4.x (the one Firebase BoM 35 uses) and drops that copy.
        implementation("com.google.firebase:protolite-well-known-types:19.0.0")
    }

    // v5.0: Sign in with Google through Android Credential Manager (ID token only: name, photo, email)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    // v4.9.5: fake googlevideo server for the chunked reader tests
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // v5.0: FirestoreProtobufTest compiles against the protobuf runtime NewPipe already forces at runtime
    testImplementation("com.google.protobuf:protobuf-javalite:4.36.2")
    // hotfix 4.9.10: real Android views in JVM tests (the crash reproduction + crash catcher tests)
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}
