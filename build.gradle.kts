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
        }
    }
}
