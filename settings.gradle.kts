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
