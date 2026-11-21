// Etappe 0: Wegwerf-Test, ob Streamplayer als native iPad-App (Compose Multiplatform)
// ohne Mac gebaut und über SideStore installiert werden kann. Eigener Gradle-Build,
// unabhängig von der Android-App im übrigen Repo.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "streamplayer-ios-spike"
include(":shared")
