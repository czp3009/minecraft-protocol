plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.ksp.api)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
