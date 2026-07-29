pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "minecraft-protocol"

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        google()
    }
}

include(
    ":nbt",
    ":protocol-model",
    ":protocol-serialization",
    ":protocol-vanilla-data",
    ":protocol-transport",
    ":protocol-session",
    ":protocol-auth",
    ":protocol-client",
    ":protocol-server",
    ":world-format",
    ":world-io",
)
