import com.hiczp.minecraft.buildlogic.BuildVersions

plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.kotlinx.serialization.json)
}
