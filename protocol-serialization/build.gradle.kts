import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.serialization")
    createHostProcessTestSourceSet("externalProcessTest") {
        dependencies {
            implementation(project(":minecraft-test-support"))
            implementation(project(":protocol-transport"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":nbt"))
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

    }
}

officialDownloads { server(); codecOracle() }
