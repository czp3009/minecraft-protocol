import com.hiczp.minecraft.buildlogic.applyMinecraftFixtureArtifactsConvention
import com.hiczp.minecraft.buildlogic.applyMinecraftTestFixtureServiceConvention
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma
import org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinxRpc) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

group = "com.hiczp"
version = "0.0.1"

val minecraftTestFixtures = applyMinecraftFixtureArtifactsConvention()
applyMinecraftTestFixtureServiceConvention(minecraftTestFixtures)

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        systemProperty("kotlinx.coroutines.test.default_timeout", "2m")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
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
