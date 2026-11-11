import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Dritte App, nur für den Betreiber: zeigt die Nutzungsstatistik aller Installationen (stats/).
//
// Der Admin-Schlüssel steht nur auf diesem Rechner (local.properties: stats.adminToken) und wird in
// die APK eingebaut, damit die App auf dem Telefon ohne Abtippen geht. Diese APK deshalb nie
// weitergeben oder auf den Update-Server legen (tools/publish.sh kennt sie bewusst nicht).
// Fehlt der Schlüssel, fragt die App beim Start danach.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val adminToken = localProps.getProperty("stats.adminToken").orEmpty().trim()
val statsUrl = (findProperty("stats.url") as String?) ?: "https://streamplayer-stats.marne-gamingx.workers.dev"
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "de.marne.streamplayer.admin"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

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

    defaultConfig {
        applicationId = "de.marne.streamplayer.admin"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "STATS_URL", "\"$statsUrl\"")
        buildConfigField("String", "ADMIN_TOKEN", "\"$adminToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
