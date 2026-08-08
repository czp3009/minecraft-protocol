# protocol-session

Typed Minecraft packet sessions over `protocol-transport`.

The module targets JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node; it does not publish
browser, D8, or Wasm/WASI variants because a live session requires TCP.

`MinecraftSession`:

- maps packet IDs through `MinecraftPacketRegistry`;
- validates clientbound/serverbound direction and connection state;
- performs Handshake, Login, Configuration, and Play transitions;
- activates compression immediately after `SetCompressionPacket`;
- supports the legacy unframed server-list ping.

The caller retains direct access to the underlying Ktor-backed frame stream.
