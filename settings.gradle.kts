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
    ":nbt-serialization",
    ":protocol-model",
    ":protocol-serialization",
    ":protocol-datapack",
    ":protocol-datapack-vanilla",
    ":protocol-transport",
    ":protocol-session",
    ":account-auth",
    ":protocol-auth",
    ":protocol-client",
    ":protocol-server",
    ":world-format",
    ":world-io",
    ":minecraft-test-support",
    ":minecraft-test-fixture-host",
    ":protocol-symbol-processor",
    ":demo:launcher",
    ":demo:web-map",
)
