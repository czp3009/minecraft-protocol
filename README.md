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

| Module                                                       | Purpose                                                                |
|--------------------------------------------------------------|------------------------------------------------------------------------|
| [`compression`](compression/README.md)                       | Portable raw DEFLATE shared by network and world formats               |
| [`nbt`](nbt/README.md)                                       | Named and unnamed binary NBT over `kotlinx.io`                         |
| [`protocol-model`](protocol-model/README.md)                 | Format-independent packet payloads and shared protocol values          |
| [`protocol-serialization`](protocol-serialization/README.md) | Minecraft wire encodings and packet lookup by state, direction, and ID |
| [`protocol-vanilla-data`](protocol-vanilla-data/README.md)   | Version-matched Known Packs, registries, tags, and vanilla catalogues  |
| [`protocol-transport`](protocol-transport/README.md)         | Ktor sockets, framing, compression, and encryption                     |
| [`protocol-session`](protocol-session/README.md)             | Typed dispatch and connection-state transitions                        |
| [`protocol-auth`](protocol-auth/README.md)                   | Offline identities, session services, hashes, and cryptography         |
| [`protocol-client`](protocol-client/README.md)               | Status, Login, Configuration, and a Play-ready client connection       |
| [`protocol-server`](protocol-server/README.md)               | Connection admission and finite initial chunk/entity projection        |
| [`world-format`](world-format/README.md)                     | Filesystem-independent Anvil containers and chunk NBT composition      |
| [`world-io`](world-io/README.md)                             | World paths and filesystem-backed NBT and region stores                |

`protocol-symbol-processor`, `minecraft-test-support`, and `minecraft-test-fixture-host` are private build or test
infrastructure and are not application dependencies.

## Requirements

- A JDK with Java major version 25 or newer and `java` on `PATH`.
- An Android SDK configured through the standard Gradle mechanisms when running Android or the complete multiplatform
  test suite.
- Network access for the first build or exhaustive test run so Gradle can download dependencies and verified official
  Minecraft fixtures.

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

Packet payloads can be encoded and decoded through the generated registry:

```kotlin
val encoded = MinecraftPacketRegistry.encodePayload(packet)

val decoded = MinecraftPacketRegistry.decodePayload(
    state = encoded.key.state,
    direction = encoded.key.direction,
    id = encoded.key.id,
    payload = encoded.payload,
)
```

Use a configured `MinecraftFormat` when a physical encoding depends on negotiated context such as the chunk section
count. The module guides contain the corresponding entry points and examples:

- [`protocol-client`](protocol-client/README.md) connects to Status or Login and returns a live Play session.
- [`protocol-server`](protocol-server/README.md) binds a Ktor server socket and maps application policy into protocol
  configuration and handler APIs.
- [`nbt`](nbt/README.md) reads and writes binary NBT streams and byte arrays.
- [`world-format`](world-format/README.md) handles in-memory Anvil containers; [`world-io`](world-io/README.md) adds
  supported filesystems and world paths.

Most model, serialization, and stream APIs target common Kotlin. Socket APIs run where the configured Ktor engine
exposes TCP. `world-io` targets JVM, Android, and Native filesystems; browser-like consumers use `nbt` and
`world-format` through streams or byte arrays.

## Building and testing

Use a focused JVM suite during normal development:

```shell
./gradlew :protocol-serialization:jvmTest
```

Run every repository JVM suite with Gradle's standard selector:

```shell
./gradlew jvmTest
```

Run every configured Kotlin Multiplatform test aggregate with:

```shell
./gradlew allTests
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. The root project does not define a replacement `test` task.

Applicable standard test tasks cover portable unit tests, real Ktor sockets, official-codec differentials, a production
client against the matching official server, a matching headless official client against the production server, and an
official world generate/rewrite/reload cycle. Gradle prepares exact verified fixtures and starts the shared JVM Fixture
Host only when a test requests them. Gradle outputs and Fixture Host processes, logs, reports, and worlds remain under
`build/`; a test-local filesystem sandbox uses the system temporary directory and is removed by the test client.
Unchanged preparation is reused by Gradle.

## Minecraft release and generated data

[
`MinecraftTarget.MINECRAFT_VERSION`](buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/MinecraftTarget.kt)
is the single manually selected Minecraft release. Print it with:

```shell
./gradlew -q minecraftVersion
```

To change the target, update that constant and run the affected standard build or test tasks. The complete official
analysis layer is also available directly:

```shell
./gradlew officialMinecraftAnalysis
```

Gradle downloads and verifies the matching official server, writes deterministic analysis below
`build/generated/official-minecraft/<version>/`, and generates version-dependent Kotlin in the owning modules' build
directories. Generated Kotlin and target evidence are not checked into the source tree.

The matching official server JAR is the primary behavioral authority. The revision-matched Minecraft Wiki is secondary,
followed by exact-version MCProtocolLib and Minestom. See [AGENTS.md](AGENTS.md) for repository development rules and
module ownership.
