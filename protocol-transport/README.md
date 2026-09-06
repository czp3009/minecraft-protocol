# protocol-transport

Kotlin Multiplatform transport primitives for Minecraft Java Edition. Real TCP targets are JVM, Android, supported
Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node.

The module provides:

- VarInt21 packet framing;
- Minecraft's zlib compression envelope;
- AES/CFB8 stream encryption;
- framed connections backed by Ktor `Socket`, `ByteReadChannel`, and `ByteWriteChannel`.

Its public boundary ends at packet-data bytes—protocol states and typed packet encoding are implemented by
[`protocol-session`](../protocol-session/README.md).

`MinecraftFrameCodec` and `MinecraftFrameStream` expose caller-owned `kotlinx.io` `Source`/`Sink` operations as their
canonical paths, with byte-array overloads as adapters. Malformed framing, compression, and transport data are exposed
through the `kotlinx.io.IOException` hierarchy. The in-memory adapter can frame, compress, and decode packet data
without opening a socket:

```kotlin
val packetData = byteArrayOf(0x01, 0x02, 0x03)
val minecraftFrameCodec = MinecraftFrameCodec().apply {
    configureCompression(threshold = 256)
}

val frame = minecraftFrameCodec.encodeFrame(packetData)
check(minecraftFrameCodec.decodeFrame(frame).contentEquals(packetData))
```

## Socket ownership and flushing

`MinecraftTransport` owns one connected Ktor `Socket` and exposes its `minecraftFrameStream`. This example takes
ownership of a caller-connected socket, sends one serialized packet and receives the next packet-data value. Obtain
that socket from Ktor `aSocket(selectorManager).tcp().connect(host, port)`, with a caller-owned
`SelectorManager(Dispatchers.Default)` kept open for the socket lifetime. `packetData` is a serialized packet ID plus
body, supplied by the session layer; the byte-array example above can exercise framing without Minecraft semantics:

```kotlin
suspend fun exchangePacketData(socket: Socket, packetData: ByteArray): ByteArray =
    MinecraftTransport(socket).use { minecraftTransport ->
        val minecraftFrameStream = minecraftTransport.minecraftFrameStream
        minecraftFrameStream.sendPacketData(packetData)
        minecraftFrameStream.flush()
        minecraftFrameStream.receivePacketData()
    }
```

Construct `MinecraftFrameStream` directly over a caller-owned `ByteReadChannel`/`ByteWriteChannel` pair for other
transports. `sendPacketData` appends a complete frame without flushing its pending tail; `receivePacketData` reads the
next frame's packet data. Use one sequential writer and one sequential reader, which may run concurrently.

[protocol-session](../protocol-session/README.md) owns compression/encryption transition ordering for typed connections.
Low-level implementations use `sendPacketDataAndCommit` to append a transition frame and commit the corresponding state
change at the same wire boundary. Flushing is a separate operation.

For a Ktor `Socket`, `flush()` publishes the pending `ByteWriteChannel` bytes to the socket's writer coroutine. It may
suspend when Ktor's bounded channel buffer has no free space, but returning means neither that the operating system has
delivered the bytes nor that the peer has decoded them. TCP and Minecraft acknowledgements remain separate layers. Ktor
makes progress as its own write buffer fills. The explicit flush publishes the remaining tail at the caller's chosen
boundary.

The typed connection in protocol-session supplies both packet pumps and arbitrates application and endpoint-generated
packets through one writer. Bundle handling and KeepAlive remain above this transport layer.
