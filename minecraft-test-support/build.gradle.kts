import com.hiczp.minecraft.protocol.buildScript.BuildVersions
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet
import com.hiczp.minecraft.protocol.buildScript.publishCodecOracleSource
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm()

    mingwX64()
    linuxArm64()
    linuxX64()
    macosArm64()

    js {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    createHostProcessTestSourceSet()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":protocol-model"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
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
                implementation(libs.xmlutil.serialization)
            }
        }
        linuxMain {
            dependsOn(kmpProcessMain)
        }
        macosMain {
            dependsOn(kmpProcessMain)
        }
        webMain {
            dependsOn(kmpProcessMain)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

    }
}

publishCodecOracleSource()
