import com.hiczp.minecraft.protocol.buildScript.BuildVersions
import com.hiczp.minecraft.protocol.buildScript.publishCodecOracleSource
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinxRpc)
}

kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)

    jvm {
        compilerOptions {
            jvmTarget.set(
                JvmTarget.fromTarget(BuildVersions.JAVA_VERSION.toString()),
            )
        }
    }

    sourceSets {
        jvmMain.dependencies {
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
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

publishCodecOracleSource()
