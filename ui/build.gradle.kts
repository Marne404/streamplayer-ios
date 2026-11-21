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
    iosArm64 {
        // iPad-App (ios/): XcodeGen-Hülle, gebaut auf GitHub Actions (macOS).
        compilerOptions {
            optIn.addAll(
                "kotlinx.cinterop.ExperimentalForeignApi",
                "kotlinx.cinterop.BetaInteropApi",
                "kotlin.io.encoding.ExperimentalEncodingApi",
                "kotlin.time.ExperimentalTime",
            )
        }
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

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
            // JSON aus dem Konto lesen (SyncJson) – ohne org.json, das es nur auf Android gibt.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        iosMain.dependencies {
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}")
            implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
        }
    }
}

// detect.js gibt es nur einmal: in der Android-App (assets). Das iPad bekommt beim Bauen eine Kopie
// als Compose-Ressource files/detect.js.
val detectJsForIos by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/detect.js"))
    into(layout.buildDirectory.dir("generated/iosDetect/files"))
}

compose.resources {
    packageOfResClass = "de.marne.streamplayer.ui.res"
    publicResClass = true
    customDirectory(
        sourceSetName = "iosMain",
        directoryProvider = detectJsForIos.map { layout.buildDirectory.dir("generated/iosDetect").get() },
    )
}
