import com.hiczp.minecraft.buildlogic.applyMinecraftFixtureArtifactsConvention
import com.hiczp.minecraft.buildlogic.applyMinecraftTestFixtureServiceConvention
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithTests
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma
import org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha
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
        extensions.configure<KotlinMultiplatformExtension> {
            // A disabled non-host test task still builds its link dependencies. Excluding the test task instead removes
            // its target-specific compile and link chain from aggregate tasks such as allTests.
            targets.withType<KotlinNativeTargetWithTests<*>>().all {
                if (konanTarget != nativeHost) {
                    gradle.startParameter.setExcludedTaskNames(
                        gradle.startParameter.excludedTaskNames + "${name}Test",
                    )
                }
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
