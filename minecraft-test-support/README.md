# Minecraft test support

`minecraft-test-support` is the repository's portable API for tests that need an exact official Minecraft server, a
prepared headless official client, or the official codec oracle.

Gradle connects supported test tasks to a private Fixture Host. Test code receives serializable resource handles and
never launches a process or locates official artifacts itself.

This is private test infrastructure, not a runtime dependency for library users.

## Start an official server

Create a server inside the structured `use` helper:

```kotlin
@Test
fun officialServerStatus() = runTest {
    MinecraftTestSupport.newOfficialServer().use { server ->
        val endpoint = server.endpoint
        val status = MinecraftTestSupport.status(server)

        assertTrue(status.alive)
        assertEquals("127.0.0.1", endpoint.host)
    }
}
```

The default configuration starts from the prepared stopped template. Supplying any custom server property starts a fresh
workspace from the prepared runtime:

```kotlin
val server = MinecraftTestSupport.newOfficialServer(
    OfficialMinecraftServerConfiguration(
        properties = mapOf(
            "online-mode" to "false",
            "level-name" to "interop-world",
        ),
    ),
)
```

Use `sendCommand`, `waitForLog`, `logText`, `restartServer`, `status`, and `awaitExit` for explicit lifecycle scenarios.
When a command has a completion marker, pass it as `sendCommand`'s `expectedNewOutput`; the Host records the pre-command
output sequence and accepts only a later matching line.

## Drive a headless official client

`newHeadlessClient` returns after HMC-Specifics is ready and a correlated GUI query observes the vanilla title screen.
It does not connect automatically:

```kotlin
suspend fun connectOfficialClient(
    testServerPort: Int,
    recordControlState: (HeadlessMinecraftClientState) -> Unit,
    verifyPacketsObservedByTestServer: suspend () -> Unit,
) {
    MinecraftTestSupport.newHeadlessClient(
        HeadlessMinecraftClientConfiguration(playerName = "FixturePlayer"),
    ).use { client ->
        val endpoint = MinecraftTestEndpoint("127.0.0.1", testServerPort)
        val state = MinecraftTestSupport.connectHeadlessClient(client, endpoint)

        recordControlState(state)
        verifyPacketsObservedByTestServer()
    }
}
```

The parameters make the loopback server, optional diagnostic recording, and packet-level assertion explicit.

`connectHeadlessClient` returns a correlated post-command `HeadlessMinecraftClientState`. `headlessClientState` requests
another correlated snapshot, and `disconnectHeadlessClient` waits for a newly observed title screen.

GUI snapshots are control/liveness evidence only. The accepting server proves the TCP connection, and packets observed
by the consuming protocol test prove Login, Configuration, or Play.

`sendHeadlessClientCommand` exposes the small audited HMC-Specifics action surface for tests that need another explicit
client action.

## Stop a process but inspect its files

The normal `use` helper schedules process and workspace cleanup. A filesystem interoperability test can instead separate
the stages. The `server` value below is an `OfficialMinecraftServer` previously returned by `newOfficialServer()`, and
`inspectStoppedWorld` is the test's same-host filesystem assertion:

```kotlin
val exitCode = MinecraftTestSupport.closeProcess(server)
check(exitCode == 0)

val hostPath = MinecraftTestSupport.hostWorkingDirectory(server)
inspectStoppedWorld(hostPath)

MinecraftTestSupport.deleteWorkingDirectory(server)
```

`hostWorkingDirectory` is intentionally non-portable. It is valid only when the test process and Fixture Host share a
filesystem namespace, and the Host owns the path until deletion. The repository uses this backdoor only for `world-io`
interoperability after the official process has stopped.

`closeAndAwait` performs both synchronous stages when no file inspection is needed. `close` schedules the same combined
cleanup and returns after the Host accepts it.

## Verify official codecs

`verifyOfficialCodec`, `verifyOfficialNbt`, and `verifyOfficialSnbt` send structured fixture values to the matching
official implementation. Success returns normally; failure throws with bounded diagnostics. No standalone success report
or result file is produced.

## Supported test tasks

Fixture entries run on configured standard tasks with usable TCP support: JVM, Android host, desktop Native, JS Node,
and WasmJS Node. Browser and D8 tasks filter fixture entries. The private Wasm/WASI target is a compile scaffold, not
runtime fixture support.

Place an annotated official-peer entry in a package containing `fixturetest`. Portable scenario code remains in the
owning module's shared test source set. The `world-io` same-filesystem scenario is the sole exception and lives in its
`hostFilesystemTest` capability source set.

The host implementation is documented in [`minecraft-test-fixture-host`](../minecraft-test-fixture-host/README.md).
