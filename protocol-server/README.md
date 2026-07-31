# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

It provides Ktor TCP binding and accepted connections, Status, offline or injected online Login, vanilla Configuration
data synchronization, and a Play-ready connection result. `MinecraftInitialWorld` can send a finite flat chunk
projection and initial entity snapshots. It does not own a gameplay loop: after bootstrap, the application owns every
subsequent packet.

The JVM suite uses the production client and server over loopback, decodes real chunk/entity packets, and runs without a
display or installed Minecraft runtime.

The standard `jvmTest` task launches the matching official client through an SHA-256-verified HeadlessMC adapter, with
all client artifacts prepared under the root project's `build/` directory. In offline mode it must
complete Configuration, process Play Login and the initial world, acknowledge teleportation and the chunk batch, emit
client ticks, and answer a Play KeepAlive. GUI client infrastructure is intentionally excluded.
