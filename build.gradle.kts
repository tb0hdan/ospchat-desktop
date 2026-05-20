import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "com.ospchat.desktop"

// Single source of truth for the desktop module's version. Mirrors
// ospchat-android's pattern (top-level VERSION file).
val projectVersion: String =
    providers
        .fileContents(layout.projectDirectory.file("VERSION"))
        .asText
        .get()
        .trim()
version = projectVersion

// jpackage requires MAJOR > 0, so a pre-1.0 version has its leading "0."
// rewritten to "1." (0.1.3 -> 1.1.3) so installer filenames still track
// the real version's minor/patch. The runtime [BuildInfo.VERSION] shown
// in About reads the real version verbatim.
val packagingVersion: String =
    if (projectVersion.startsWith("0.")) "1." + projectVersion.removePrefix("0.") else projectVersion

// Optional `-PmacArch=x86_64|arm64` from the release workflow so the
// per-arch macOS matrix entries produce distinguishable .dmg filenames
// (e.g. OSPChat-1.1.3-x86_64.dmg vs OSPChat-1.1.3-arm64.dmg). Absent on
// Linux/Windows and on local builds, where packageName stays "OSPChat".
val macArch: String? = providers.gradleProperty("macArch").orNull
val installerPackageName: String =
    if (macArch != null) "OSPChat-$macArch" else "OSPChat"

// webrtc-java publishes platform-specific classifier jars (per-OS, per-arch)
// containing the native libs. The main artifact is the API-only jar. Detect
// host OS + arch at configure time so the build pulls in the matching native
// jar for whichever machine is building — matches the existing release matrix
// (Linux x86_64 / macOS x86_64 / macOS aarch64 / Windows x86_64) where each
// runner only ever needs its own platform's natives.
val webrtcJavaClassifier: String =
    run {
        val osNameLower = System.getProperty("os.name").lowercase()
        val osArchLower = System.getProperty("os.arch").lowercase()
        val os =
            when {
                osNameLower.contains("linux") -> "linux"
                osNameLower.contains("mac") || osNameLower.contains("darwin") -> "macos"
                osNameLower.contains("windows") -> "windows"
                else -> error("Unsupported host OS for webrtc-java: $osNameLower")
            }
        val arch =
            when {
                osArchLower.contains("aarch64") || osArchLower.contains("arm64") -> "aarch64"
                osArchLower.contains("amd64") || osArchLower.contains("x86_64") -> "x86_64"
                osArchLower.contains("arm") -> "aarch32"
                else -> error("Unsupported host arch for webrtc-java: $osArchLower")
            }
        "$os-$arch"
    }

// Developer ID signing for macOS is a no-op unless `-PmacSigningIdentity=...`
// is passed (or ORG_GRADLE_PROJECT_macSigningIdentity is in the env). An
// unsigned .dmg still installs and runs locally, but every relaunch
// re-prompts macOS' application firewall because ALF keys its allow/deny
// memory on a binary's Designated Requirement, which only exists for code
// signed with a stable identity. Flip this property in CI once a paid
// Apple Developer ID cert is provisioned to make ALF remember its answer.
val macSigningIdentity: String? = providers.gradleProperty("macSigningIdentity").orNull
val macSigningKeychain: String? = providers.gradleProperty("macSigningKeychain").orNull

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(libs.ospchat.shared)
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.slf4j.nop)
                implementation(libs.metadata.extractor)
                // libwebrtc JNI bindings. Two artifacts required: the Java
                // API (`webrtc-java`) and the host-specific native jar
                // (`webrtc-java:<classifier>`). See webrtcJavaClassifier above.
                implementation(libs.webrtc.java)
                implementation("${libs.webrtc.java.get().module}:${libs.versions.webrtcJava.get()}:$webrtcJavaClassifier")
            }
        }
    }
}

// Emit a tiny Kotlin source file that surfaces the VERSION-derived value at
// runtime (used by AboutScreen). Keeps "what version am I" out of the
// hard-coded constant pool.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildInfo")
    val version = projectVersion
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val target =
            outputDir
                .get()
                .asFile
                .resolve("com/ospchat/desktop/BuildInfo.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            // GENERATED — do not edit. Source of truth: ../VERSION
            package com.ospchat.desktop

            internal object BuildInfo {
                const val VERSION: String = "$version"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets.named("desktopMain") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}

compose.desktop {
    application {
        mainClass = "com.ospchat.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = installerPackageName
            packageVersion = packagingVersion
            description = "Open-source LAN chat (desktop)"
            vendor = "OSPChat"
            // androidx.datastore's bundled protobuf-lite reaches into sun.misc.Unsafe,
            // which lives in jdk.unsupported. jdeps can't detect uses of sun.misc.*
            // so jlink omits the module from the bundled JRE unless we ask for it.
            modules("jdk.unsupported")

            // Mirror ospchat-android's launcher icon. Each platform expects its
            // own format: .icns for macOS, .ico for Windows, .png for Linux.
            // Sources live in icons/ and are regenerated from icons/icon.svg
            // via the make target `make icons`.
            val iconsDir = project.layout.projectDirectory.dir("icons")
            linux {
                iconFile.set(iconsDir.file("icon.png"))
            }
            macOS {
                iconFile.set(iconsDir.file("icon.icns"))

                // Stable identifier jpackage writes into Info.plist's
                // CFBundleIdentifier and reuses for LaunchServices, ALF, and
                // TCC scoping. jpackage will otherwise synthesise one and the
                // value can drift between builds — defeating any allow rule
                // the user has already clicked through.
                bundleID = "com.ospchat.desktop"
                appCategory = "public.app-category.social-networking"

                // macOS 15 (Sequoia) gates outbound .local resolution and
                // mDNS multicast on the Local Network privacy prompt. JmDNS's
                // outbound 5353 traffic is subject to this; declaring the
                // service type up front means the system can render a
                // meaningful prompt instead of just denying silently.
                // Inbound TCP (the Ktor listener) is exempt from this prompt
                // but still gated by ALF — that needs code signing, see below.
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_ospchat._tcp</string>
                        </array>
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>OSPChat finds other OSPChat clients on your local network so you can chat directly, with no server in the middle.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>OSPChat uses your microphone for voice calls with other OSPChat users on your local network.</string>
                        """.trimIndent()
                }

                // Wire a Developer ID identity if one was supplied via
                // -PmacSigningIdentity=... — otherwise leave the build
                // unsigned (sign=false default). With a stable signature
                // ALF can persist the user's "Allow" decision across launches
                // instead of re-prompting on every relaunch.
                if (macSigningIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(macSigningIdentity)
                        if (macSigningKeychain != null) {
                            keychain.set(macSigningKeychain)
                        }
                    }
                    entitlementsFile.set(project.layout.projectDirectory.file("macos/entitlements.plist"))
                    runtimeEntitlementsFile.set(project.layout.projectDirectory.file("macos/entitlements.plist"))
                }
            }
            windows {
                iconFile.set(iconsDir.file("icon.ico"))
            }
        }
    }
}
