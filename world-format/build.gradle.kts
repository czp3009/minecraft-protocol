import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.JvmProcessArguments
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// NativeBuilds' published Gradle plugin still calls a Gradle Kotlin DSL internal removed in Gradle 9.6.1. Consume
// its official binary/header artifacts directly and use public Gradle/Kotlin APIs until the plugin is compatible.
val lz4Headers = configurations.create("lz4Headers") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val lz4HeadersDependency = dependencies.create(libs.nativebuilds.lz4.headers.get()) as ExternalModuleDependency
lz4HeadersDependency.artifact {
    type = "zip"
    extension = "zip"
}
lz4Headers.dependencies.add(lz4HeadersDependency)

val lz4HeadersDirectory = layout.buildDirectory.dir("nativebuilds/lz4-headers")
val extractLz4Headers = tasks.register("extractLz4Headers", Sync::class) {
    description = "extractLz4Headers"
    from(lz4Headers.incoming.files.elements.map { archives ->
        archives.map { archive -> zipTree(archive) }
    })
    into(lz4HeadersDirectory)
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
        namespace = "com.hiczp.minecraft.world.format"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
    }

    js {
        nodejs()
        browser()
    }

    wasmJs {
        nodejs()
        browser()
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("lz4") {
            definitionFile.set(file("src/nativeMain/cinterop/lz4.def"))
            includeDirs(lz4HeadersDirectory.map { it.dir("common") })
        }
    }

    // commonMain owns the public kotlinx-io contract and shared LZ4Block framing. The default hierarchy has no
    // JVM + Android + Native intersection for the implementation-only Okio codecs, nor a JVM + Android intersection
    // for lz4-java. Keep the two capability source sets linear:
    // commonMain <- okioCompressionMain <- javaLz4Main <- {jvmMain, androidMain}; nativeMain depends directly on
    // okioCompressionMain. Web targets stay entirely on the default webMain/jsMain/wasmJsMain hierarchy.
    sourceSets {
        val okioCompressionMain = create("okioCompressionMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.io.okio)
                implementation(libs.okio)
            }
        }
        val javaLz4Main = create("javaLz4Main") {
            dependsOn(okioCompressionMain)
            dependencies {
                implementation(libs.lz4.java)
            }
        }
        jvmMain {
            dependsOn(javaLz4Main)
        }
        androidMain {
            dependsOn(javaLz4Main)
        }
        nativeMain {
            dependsOn(okioCompressionMain)
            dependencies {
                implementation(libs.appmattus.cryptohash)
                implementation(libs.nativebuilds.lz4)
            }
        }

        commonMain.dependencies {
            api(project(":nbt"))
            api(project(":nbt-serialization"))
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
        webMain.dependencies {
            implementation(libs.kompress.core)
            implementation(libs.kompress.zlib)
            implementation(libs.kotlinx.browser)
        }
        jsMain.dependencies {
            implementation(npm("lz4-lite", libs.versions.lz4.lite.get()))
            implementation(npm("js-xxhash", libs.versions.js.xxhash.get()))
        }
        wasmJsMain.dependencies {
            implementation(npm("lz4-lite", libs.versions.lz4.lite.get()))
            implementation(npm("js-xxhash", libs.versions.js.xxhash.get()))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.json.io)
        }

        jvmTest.dependencies {
            implementation(libs.lz4.java)
        }
    }
}

tasks.withType<CInteropProcess>().configureEach {
    dependsOn(extractLz4Headers)
}

tasks.withType<Test>().configureEach {
    jvmArgs(JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
}
