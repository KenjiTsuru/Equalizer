import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

// Read secrets from local.properties (NOT committed to git) instead of
// hardcoding them in source. Every dev/CI machine keeps its own local.properties.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.autoeq"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.autoeq"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SPOTIFY_CLIENT_ID",
            "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    // Spotify Auth Library — handles OAuth login + gives you the access token
    // you use to call the Spotify Web API (playlists, currently playing, etc.)
    implementation("com.spotify.android:auth:5.0.0")

    // Spotify App Remote SDK — only needed if you also want live playback
    // control/metadata straight from the on-device Spotify app. Spotify
    // doesn't publish this one to Maven Central or JitPack reliably, so we
    // use the .aar downloaded from https://github.com/spotify/android-sdk/releases
    // and dropped into app/libs/. It already bundles the com.spotify.protocol.*
    // classes (Track, PlayerState, etc.), so nothing else is needed for it.
    implementation(fileTree("libs") { include("*.jar", "*.aar") })

    // For calling the Spotify Web API (GET /me/playlists, /me/player/currently-playing)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}