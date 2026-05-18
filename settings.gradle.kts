pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// ospchat-shared is consumed from mavenLocal. After any change in
// ospchat-shared, run `gradle publishToMavenLocal` over there to refresh the
// artifact. Composite-build would be cleaner but currently hits a KGP
// BuildFusService classloader conflict between two KMP builds.

rootProject.name = "ospchat-desktop"
