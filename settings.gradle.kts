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
    ":compression",
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
    ":minecraft-test-support",
    ":minecraft-test-fixture-host",
    ":protocol-symbol-processor",
)
