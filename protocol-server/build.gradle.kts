import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.protocol.server",
        includeWasmWasi = false,
        includeWasmJsD8 = false,
        nodeTestTimeout = "60m",
    )
    createHostProcessTestSourceSet("externalProcessTest") {
        dependencies {
            implementation(project(":minecraft-test-support"))
            implementation(libs.kotlinx.serialization.json)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(project(":protocol-vanilla-data"))
            api(libs.ktor.network)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":protocol-client"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

    }
}

officialDownloads { client(); headlessMc() }
