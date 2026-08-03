import com.hiczp.minecraft.protocol.buildScript.BuildVersions

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.ksp.api)
            implementation(libs.kotlinpoet)
            implementation(libs.kotlinpoet.ksp)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
