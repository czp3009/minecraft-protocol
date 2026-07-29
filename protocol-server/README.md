# protocol-server

A Kotlin Multiplatform server-side Minecraft Java Edition protocol API.

It provides Ktor TCP binding and accepted connections, Status, offline or injected online Login, vanilla Configuration
data synchronization, and a Play-ready connection result. `MinecraftInitialWorld` can send a finite flat chunk
projection and initial entity snapshots. It does not own a gameplay loop: after bootstrap, the application owns every
subsequent packet.

The JVM and layer suites use the production client and server over loopback, decode real chunk/entity packets, and run
without a display or installed Minecraft runtime.

`headlessOfficialClientToServerEndToEndTest` launches the matching official client through a SHA-256-verified HeadlessMC
adapter, with all client artifacts prepared under the root project's `build/` directory. In offline mode it must
complete Configuration, process Play Login and the initial world, acknowledge teleportation and the chunk batch, emit
client ticks, and answer a Play KeepAlive. `officialClientToServerEndToEndTest` exercises the same server path through a
direct desktop launch when a graphical environment is available.
