import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets("com.hiczp.minecraft.compression")

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
