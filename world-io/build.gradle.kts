import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.useMinecraftTestFixtures

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm()

    mingwX64()
    linuxArm64()
    linuxX64()
    macosArm64()

    iosSimulatorArm64()
    iosArm64()
    iosX64()

    watchosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()

    tvosSimulatorArm64()
    tvosArm64()

    android {
        namespace = "com.hiczp.minecraft.world.io"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
    }

    js {
        nodejs()
    }

    useMinecraftTestFixtures(
        requiresOfficialServer = true,
        requiresHeadlessClient = true,
    )

    sourceSets {
        val javaNioMain = create("javaNioMain") {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(javaNioMain)
        }
        androidMain {
            dependsOn(javaNioMain)
        }
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        appleMain {
            dependsOn(posixMain)
        }
        linuxMain {
            dependsOn(posixMain)
        }

        commonMain.dependencies {
            api(project(":nbt"))
            api(project(":nbt-serialization"))
            api(project(":world-format"))
            api(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
        }
        jsMain.dependencies {
            implementation(libs.okio.nodefilesystem)
            implementation(npm("fs-native-extensions", "1.5.0"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":minecraft-test-support"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }

        val hostFilesystemTest = create("hostFilesystemTest") {
            dependsOn(commonTest.get())
        }
        jvmTest {
            dependsOn(hostFilesystemTest)
        }
        jsTest {
            dependsOn(hostFilesystemTest)
        }
        mingwTest {
            dependsOn(hostFilesystemTest)
        }
        linuxTest {
            dependsOn(hostFilesystemTest)
        }
        macosTest {
            dependsOn(hostFilesystemTest)
        }
    }
}
