import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.world.io",
        includeWasmWasi = false,
        includeJs = false,
        includeWasmJs = false,
    )

    sourceSets {
        commonMain.dependencies {
            api(project(":world-format"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(project(":minecraft-test-support"))
        }
    }
}
