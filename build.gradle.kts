import com.hiczp.minecraft.buildlogic.applyMinecraftFixtureArtifactsConvention
import com.hiczp.minecraft.buildlogic.applyMinecraftTestFixtureServiceConvention
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma
import org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinxRpc) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.ktorfit) apply false
}

group = "com.hiczp"
version = "0.0.1"

val nativeHost = HostManager.host

val minecraftTestFixtures = applyMinecraftFixtureArtifactsConvention()
applyMinecraftTestFixtureServiceConvention(minecraftTestFixtures)

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        systemProperty("kotlinx.coroutines.test.default_timeout", "2m")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        // Cross-host KLIB compilation is portable, but final linking can consume target-native libraries whose
        // toolchains are incompatible with the current host. Final binaries are therefore built on their own host.
        tasks.withType<KotlinNativeLink>().configureEach {
            if (binary.target.konanTarget != nativeHost) {
                onlyIf("the Kotlin/Native binary target matches the current host") { false }
            }
        }

        tasks.withType<KotlinJsTest>().configureEach {
            onTestFrameworkSet {
                when (this) {
                    is KotlinKarma -> useConfigDirectory(
                        compilation.target.project.rootDir
                            .resolve("karma.config.d"),
                    )

                    is KotlinMocha -> timeout = "2m"
                }
            }
        }
    }
}
