import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.JvmProcessArguments
import com.hiczp.minecraft.buildlogic.useMinecraftTestFixtures
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
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
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.io.core)
            api(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kotlinx.serialization.json.io)
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

tasks.withType<Test>().configureEach {
    jvmArgs(JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
}

tasks.withType<KotlinJsTest>().configureEach {
    onTestFrameworkSet {
        if (this is KotlinMocha) timeout = "6m"
    }
}
