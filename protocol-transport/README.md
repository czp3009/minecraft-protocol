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

`MinecraftTransport` owns one Ktor `Socket` and exposes its `minecraftFrameStream`. A caller using this low-level API
changes compression or encryption immediately after appending the complete transition packet frame. Here `socket` is a
caller-connected Ktor socket, the two `...PacketData` values are already serialized packet payloads, and `sharedSecret`
is the Login key-exchange result:

```kotlin
val minecraftTransport = MinecraftTransport(socket)
val minecraftFrameStream = minecraftTransport.minecraftFrameStream

minecraftFrameStream.sendPacketData(setCompressionPacketData)
minecraftFrameStream.configureCompression(threshold = 256)

minecraftFrameStream.sendPacketData(encryptionResponsePacketData)
minecraftFrameStream.enableEncryption(sharedSecret)

val packetData = minecraftFrameStream.receivePacketData()
minecraftFrameStream.sendPacketData(packetData)
minecraftFrameStream.flush()
minecraftTransport.close()
```

Construct `MinecraftFrameStream` directly over any caller-owned `ByteReadChannel`/`ByteWriteChannel` pair when the
connection is not a plain `Socket`. [`protocol-session`](../protocol-session/README.md) owns transition ordering for
typed connections.

## Flush and socket backpressure

`sendPacketData` writes one complete frame to the `ByteWriteChannel` without flushing its pending tail. This lets a
caller encode several packets and publish them together. `minecraftFrameStream` is the stream created in the preceding
example; `firstPacketData` and `secondPacketData` are two caller-serialized packet payloads:

```kotlin
minecraftFrameStream.sendPacketData(firstPacketData)
minecraftFrameStream.sendPacketData(secondPacketData)
minecraftFrameStream.flush()
```

For a Ktor `Socket`, `flush()` publishes the pending `ByteWriteChannel` bytes to the socket's writer coroutine. It may
suspend when Ktor's bounded channel buffer has no free space, but returning means neither that the operating system has
delivered the bytes nor that the peer has decoded them. TCP and Minecraft acknowledgements remain separate layers. Ktor
makes progress as its own write buffer fills. The explicit flush publishes the remaining tail at the caller's chosen
boundary.

One coroutine owns sequential reads and one coroutine owns sequential writes. The two directions may run concurrently;
callers do not issue concurrent operations within one direction. The typed connection in `protocol-session` provides
these two pumps and arbitrates public and endpoint-generated packets through one writer for ordinary use. Logical Bundle
handling and KeepAlive policy remain above this transport layer.
