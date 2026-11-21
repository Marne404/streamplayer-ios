// Gemeinsame Oberfläche für Android und iPad (Compose Multiplatform): Ivory & Ink, die
// Datenklassen der Bibliothek und ihre Screens. Paketnamen wie vorher in core/app, damit sich an
// den Aufrufern nichts ändert. Plattform-Eigenes (Speicher, WebView, Player) bleibt in app/.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "de.marne.streamplayer.ui"
        compileSdk = 37
        minSdk = 29
        androidResources { enable = true }
    }
    iosArm64()

    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            val cmp = libs.versions.composeMultiplatform.get()
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:${libs.versions.cmpMaterial3.get()}")
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api(libs.coil.compose)
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
            api("org.jetbrains.kotlinx:kotlinx-datetime:${libs.versions.kotlinxDatetime.get()}")
        }
        iosMain.dependencies {
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}")
            implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
        }
    }
}

compose.resources {
    packageOfResClass = "de.marne.streamplayer.ui.res"
    publicResClass = true
}
