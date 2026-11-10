plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Gemeinsam für Player und Creator: Ivory & Ink, das Katalogformat, Muster und TMDB.
android {
    namespace = "de.marne.streamplayer.core"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
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
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
