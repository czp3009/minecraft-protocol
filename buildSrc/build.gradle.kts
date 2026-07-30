plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.kotlin.multiplatform.library.plugin)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.yaml:snakeyaml:2.5")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}
