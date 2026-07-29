import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.data")

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            implementation(project(":protocol-serialization"))
            implementation(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

tasks.register<Test>("vanillaDataLayerTest") {
    group = "verification"
    description = "Test generated vanilla registries, tags, and static protocol data."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
}
