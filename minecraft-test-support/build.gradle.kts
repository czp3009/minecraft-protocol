import com.hiczp.minecraft.protocol.buildScript.configureDesktopNativeTargets
import com.hiczp.minecraft.protocol.buildScript.configureWebTargets
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet
import com.hiczp.minecraft.protocol.buildScript.publishCodecOracleSource

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(25)
    applyDefaultHierarchyTemplate()

    jvm()
    configureDesktopNativeTargets()
    configureWebTargets(
        includeWasmJsD8 = false,
        includeWasmWasi = false,
        nodeTestTimeout = "60m",
    )

    createHostProcessTestSourceSet("hostProcessTest")

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.network)
            implementation(libs.kotlincrypto.hash.md)
            implementation(libs.kotlincrypto.hash.sha1)
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlin.logging)
        }

        val kmpProcessMain = create("kmpProcessMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kmp.process)
            }
        }

        jvmMain {
            dependsOn(kmpProcessMain)
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.xmlutil.serialization)
            }
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
        linuxMain {
            dependsOn(kmpProcessMain)
        }
        macosMain {
            dependsOn(kmpProcessMain)
        }
        webMain {
            dependsOn(kmpProcessMain)
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

    }
}

officialDownloads { server(); client(); headlessMc() }
publishCodecOracleSource()
