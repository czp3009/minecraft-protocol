# minecraft-protocol

> This is an early-stage experimental project. It is not ready for production use.

`minecraft-protocol` is a Kotlin Multiplatform library for the Minecraft Java Edition network protocol and world-storage
formats. It provides typed packet models, `kotlinx.serialization` codecs, Ktor transport and connection orchestration,
authentication helpers, version-matched vanilla Configuration data, binary NBT, and Anvil world I/O.

The selected Minecraft release and protocol number are exposed by `MinecraftProtocol`. This repository contains
infrastructure for Minecraft applications, not a complete game server: gameplay, authoritative worlds, ticking,
persistence policy, permissions, and operations remain application responsibilities.

## Modules

Depend on the narrowest module that provides the required API. Higher layers expose their lower-layer API dependencies
transitively where needed.

| Module                                                       | Purpose                                                                           |
|--------------------------------------------------------------|-----------------------------------------------------------------------------------|
| [`nbt`](nbt/README.md)                                       | Format-independent NBT values and logical serializers                             |
| [`nbt-serialization`](nbt-serialization/README.md)           | Binary NBT and NBT tree conversion through `kotlinx.serialization`                |
| [`protocol-model`](protocol-model/README.md)                 | Format-independent packet payloads and shared protocol values                     |
| [`protocol-serialization`](protocol-serialization/README.md) | Minecraft wire encodings and packet lookup by state, direction, and ID            |
| [`protocol-vanilla-data`](protocol-vanilla-data/README.md)   | Version-matched Known Packs, registries, tags, and vanilla catalogues             |
| [`protocol-transport`](protocol-transport/README.md)         | Ktor sockets, framing, compression, and encryption                                |
| [`protocol-session`](protocol-session/README.md)             | Typed dispatch and connection-state transitions                                   |
| [`account-auth`](account-auth/README.md)                     | Microsoft OAuth, Xbox, and Minecraft Services token/entitlement/profile HTTP APIs |
| [`protocol-auth`](protocol-auth/README.md)                   | Game identities, Session Server HTTP, hashes, and Login key exchange              |
| [`protocol-client`](protocol-client/README.md)               | Status, Login, Configuration, and a Play-ready client connection                  |
| [`protocol-server`](protocol-server/README.md)               | Connection admission and finite initial chunk/entity projection                   |
| [`world-format`](world-format/README.md)                     | Filesystem-independent Anvil containers and chunk NBT composition                 |
| [`world-io`](world-io/README.md)                             | World paths and filesystem-backed NBT and region stores                           |

`protocol-symbol-processor`, `minecraft-test-support`, and `minecraft-test-fixture-host` are private build or test
infrastructure and are not application dependencies.

## Requirements

- A JDK with Java major version 25 or newer and `java` on `PATH`.
- An Android SDK configured through the standard Gradle mechanisms when running Android or the complete multiplatform
  test suite.
- Network access for the first build or exhaustive test run so Gradle can download dependencies and exact-version
  official Minecraft fixtures.

Use the checked-in Gradle wrapper; a separate Gradle installation is unnecessary. Gradle provisions Node, D8, Yarn, and
Kotlin Native tooling for configured non-JVM targets. Browser drivers, an installed Minecraft launcher, an account, and
a display server are not test prerequisites.

## Using the library from this checkout

The project does not publish a stable binary release. Source integrations use Gradle project dependencies, for example:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":protocol-client"))
        }
    }
}
```

Packet payloads can be encoded and decoded directly through caller-owned streams:

```kotlin
val encoding = MinecraftPacketRegistry.encodePayloadToSink(packet, payloadSink)
val decoded = MinecraftPacketRegistry.decodePayloadFromSource(
    state = encoding.key.state,
    direction = encoding.key.direction,
    id = encoding.key.id,
    source = payloadSource,
    byteCount = payloadByteCount,
)
```

Byte-array operations are adapters over those paths:

```kotlin
val encoded = MinecraftPacketRegistry.encodePayload(packet)

val decoded = MinecraftPacketRegistry.decodePayload(
    state = encoded.key.state,
    direction = encoded.key.direction,
    id = encoded.key.id,
    payload = encoded.payload,
)
```

Use a configured `MinecraftProtocolFormat` when a physical encoding depends on negotiated context such as the chunk
section
count. The module guides contain the corresponding entry points and examples:

- [`protocol-client`](protocol-client/README.md) connects to Status or Login and returns a live Play session.
- [`protocol-server`](protocol-server/README.md) binds a Ktor server socket and maps application policy into protocol
  configuration and handler APIs.
- [`nbt`](nbt/README.md) provides format-independent NBT values;
  [`nbt-serialization`](nbt-serialization/README.md) maps serializers to NBT trees and reads or writes binary NBT.
- [`world-format`](world-format/README.md) handles in-memory Anvil containers through `kotlinx.io` streams;
  [`world-io`](world-io/README.md) adds Okio-only filesystem access and world paths. Their READMEs define the public
  exception boundary for each layer.

Most model, serialization, and stream APIs target common Kotlin. Socket APIs run where the configured Ktor engine
exposes TCP. `world-io` targets Okio system filesystems on JVM, Android, Native, and Kotlin/JS Node; browser and Wasm
consumers use `nbt`, `nbt-serialization`, and `world-format` through trees, streams, or byte arrays.

## Building and testing

Use a focused JVM suite during normal development:

```shell
./gradlew :protocol-serialization:jvmTest
```

Run the pure JVM Fixture Host suite and every KMP JVM suite together with:

```shell
./gradlew :minecraft-test-fixture-host:test jvmTest
```

Run every configured Kotlin Multiplatform test aggregate with:

```shell
./gradlew allTests
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. The root project does not define a replacement `test` task.

Applicable standard test tasks cover portable unit tests, real Ktor sockets, official-codec differentials, a production
client against the matching official server, a matching Mojang client controlled by HMC-Specifics against the production
server, and an official world generate/rewrite/reload cycle. Gradle prepares all exact-version resources before launch,
starts each assembled server and HeadlessMC/Fabric/HMC-Specifics client once to publish a clean stopped template, and
starts the shared JVM Fixture Host only when a test requests it. Default configurations clone those templates
automatically; non-default configurations start from the prepared runtime without seeded world or client state.
Immutable fixture inputs are never launched in place. Where supported, workspace assembly uses one directory symbolic
link for the complete read-only client Minecraft runtime and one for the official-server libraries. It falls back to
per-file hard links or copies, and uses that same per-file strategy for other immutable runtime files, the HeadlessMC
launcher, HMC-Specifics, and Fabric's processed-mod cache. Mutable template state remains a private copy, and cleanup
never follows workspace directory links. Runtime HeadlessMC launches do not download resources.

Gradle outputs remain under `build/`; Fixture Host processes, worlds, and scratch workspaces are removed after use, and
successful tests do not create standalone report files. A test-local filesystem sandbox uses the system temporary
directory and is removed by the test client. Compatible E2E phases reuse one process handle owned by their annotated
test scenario and close it with structured cleanup. The Host admits at most eight concurrent fixture processes and
performs graceful shutdown, followed by forced termination and workspace cleanup when the build ends. Unchanged
preparation is reused by Gradle.

## Minecraft release and generated data

[
`MinecraftTarget.MINECRAFT_VERSION`](buildSrc/src/main/kotlin/com/hiczp/minecraft/buildlogic/MinecraftTarget.kt)
is the single manually selected Minecraft release. Print it with:

```shell
./gradlew -q minecraftVersion
```

To change the target, update that constant and run the affected standard build or test tasks. The complete official
analysis layer is also available directly:

```shell
./gradlew officialMinecraftAnalysis
```

Gradle downloads the matching official server, writes deterministic analysis below
`build/generated/official-minecraft/<version>/`, and generates version-dependent Kotlin in the owning modules' build
directories. Downloads rely on HTTP completion rather than a second content-hash or expected-size pass. Generated Kotlin
and target evidence are not checked into the source tree.

The matching official server JAR is the primary behavioral authority. The revision-matched Minecraft Wiki is secondary,
followed by exact-version MCProtocolLib and Minestom. See [AGENTS.md](AGENTS.md) for repository development rules and
module ownership.
