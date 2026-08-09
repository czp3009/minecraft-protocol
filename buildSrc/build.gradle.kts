plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinpoet)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
}
