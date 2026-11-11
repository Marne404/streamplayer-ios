import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase (Konto, Sync): nur mit google-services.json aus der Firebase-Konsole. Ohne die Datei
// baut die App trotzdem – das Konto zeigt dann „nicht eingerichtet“. Die Datei ist gitignored.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// Nur zum Testen: -Pfirebase.emulator=10.0.2.2 verbindet Debug-Builds mit dem lokalen
// Firebase-Emulator (Demo-Projekt, keine echten Daten).
val firebaseEmulator = (findProperty("firebase.emulator") as String?).orEmpty()

// Woher das Selbst-Update kommt. Nur zum Testen umbiegen (-Pupdate.url=http://10.0.2.2:8766);
// tools/publish.sh baut immer mit dem echten Worker.
val updateUrl = (findProperty("update.url") as String?) ?: "https://streamplayer-update.marne-gamingx.workers.dev"

// Release-Schlüssel liegt außerhalb des Repos; keystore.properties (gitignored) zeigt darauf.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "de.marne.streamplayer"

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "de.marne.streamplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = 26
        versionName = "1.13.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne keystore.properties (z. B. auf einem anderen Rechner) signiert der Debug-Schlüssel.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "FIREBASE_EMULATOR", "\"\"")
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
            buildConfigField("String", "STATS_URL", "\"https://streamplayer-stats.marne-gamingx.workers.dev\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "FIREBASE_EMULATOR", "\"$firebaseEmulator\"")
            buildConfigField("String", "UPDATE_URL", "\"\"")
            // Debug zählt nicht mit (Tests verfälschten die Übersicht); zum Ausprobieren: -Pstats.debugUrl=…
            buildConfigField("String", "STATS_URL", "\"${(findProperty("stats.debugUrl") as String?).orEmpty()}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.media3.ui)
    // MediaExtractorCompat: TS/fMP4 lesen wie der Player (einzelne AAC-Frames) – für FastHls.
    implementation(libs.media3.inspector)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
