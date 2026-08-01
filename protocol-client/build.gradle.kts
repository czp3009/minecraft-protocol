import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.protocol.client",
        includeWasmWasi = false,
        includeWasmJsD8 = false,
    )
    createHostProcessTestSourceSet("externalProcessTest") {
        dependencies {
            implementation(project(":minecraft-test-support"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(libs.ktor.network)
            implementation(project(":protocol-vanilla-data"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.mock)
        }
    }
}
