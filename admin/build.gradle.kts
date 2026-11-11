import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Dritte App, nur für den Betreiber: zeigt die Nutzungsstatistik aller Installationen (stats/).
//
// Der Admin-Schlüssel steht nur auf diesem Rechner (local.properties: stats.adminToken). Eine
// Hand-Installation baut ihn ein, damit die App ohne Abtippen geht; sie merkt ihn sich beim ersten
// Start. Das Selbst-Update (tools/publish.sh, -Pstats.noToken) baut OHNE Schlüssel – die APK liegt
// öffentlich auf dem Update-Server, die installierte App behält ihren gemerkten Schlüssel.
// Fehlt er ganz, fragt die App beim Start danach. Eine APK mit Schlüssel nie weitergeben.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val adminToken = if (findProperty("stats.noToken") != null) "" else localProps.getProperty("stats.adminToken").orEmpty().trim()
val updateUrl = (findProperty("update.url") as String?) ?: "https://streamplayer-update.marne-gamingx.workers.dev"
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
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "UPDATE_URL", "\"\"")
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
