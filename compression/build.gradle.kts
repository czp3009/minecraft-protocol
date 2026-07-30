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

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

tasks.register<Test>("compressionLayerTest") {
    group = "verification"
    description =
        "Test portable raw-DEFLATE blocks, limits, malformed input, and JVM differential behavior."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
}
