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
        // Local development: an unpublished `ospchat-shared` SNAPSHOT or
        // staging version produced by `make publish-local` over in
        // ../ospchat-shared can be picked up from here. Listed first so it
        // wins over the GitHub Packages copy for any version that exists in
        // both — important during shared-module development cycles.
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // ospchat-shared is consumed from GitHub Packages. Even public
        // packages require auth: set `gprUser` + `gprToken` in
        // ~/.gradle/gradle.properties (token = a PAT with `read:packages`),
        // or export GITHUB_ACTOR + GITHUB_TOKEN. Mirrors the publishing
        // block in ../ospchat-shared/build.gradle.kts and the consumer
        // setup in ../ospchat-android/settings.gradle.kts.
        maven {
            name = "GitHubPackagesOspChatShared"
            url = uri("https://maven.pkg.github.com/tb0hdan/ospchat-shared")
            credentials {
                username =
                    (settings.providers.gradleProperty("gprUser").orNull)
                        ?: System.getenv("GITHUB_ACTOR")
                password =
                    (settings.providers.gradleProperty("gprToken").orNull)
                        ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("com.ospchat")
            }
        }
    }
}

rootProject.name = "ospchat-desktop"
