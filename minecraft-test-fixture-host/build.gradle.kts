import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.publishCodecOracleSource
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinxRpc)
}

kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)

    compilerOptions {
        jvmTarget.set(
            JvmTarget.fromTarget(BuildVersions.JAVA_VERSION.toString()),
        )
    }
}

dependencies {
    implementation(project(":minecraft-test-support"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.ktor.network)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlincrypto.hash.md)
    implementation(libs.kotlincrypto.hash.sha2)
    implementation(libs.kotlin.logging)
    implementation(libs.xmlutil.serialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

publishCodecOracleSource()
