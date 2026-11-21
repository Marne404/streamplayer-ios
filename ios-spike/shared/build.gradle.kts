plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

val cmp = "1.12.1"

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    compilerOptions {
        optIn.addAll(
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlinx.cinterop.BetaInteropApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$cmp")
            implementation("org.jetbrains.compose.foundation:foundation:$cmp")
            implementation("org.jetbrains.compose.ui:ui:$cmp")
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            implementation("org.jetbrains.compose.components:components-resources:$cmp")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
}

compose.resources {
    packageOfResClass = "de.marne.spike.res"
}
