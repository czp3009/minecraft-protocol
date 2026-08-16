# protocol-transport

Kotlin Multiplatform transport primitives for Minecraft Java Edition. Real TCP targets are JVM, Android, supported
Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node.

The module provides:

- VarInt21 packet framing;
- Minecraft's zlib compression envelope;
- AES/CFB8 stream encryption;
- framed connections backed by Ktor `Socket`, `ByteReadChannel`, and `ByteWriteChannel`.

Compression and encryption delegate to maintained platform libraries; the module contains no compression or AES
algorithm implementation. Its public boundary ends at packet-data bytes—protocol states and typed packet encoding are
implemented by [`protocol-session`](../protocol-session/README.md).

`MinecraftFrameCodec` and `MinecraftFrameStream` expose caller-owned `kotlinx.io` `Source`/`Sink` operations as their
canonical paths, with byte-array overloads as adapters. Malformed framing, compression, and transport data are exposed
through the `kotlinx.io.IOException` hierarchy. The in-memory adapter can frame, compress, and decode packet data
without opening a socket:

```kotlin
val packetData = byteArrayOf(0x01, 0x02, 0x03)
val codec = MinecraftFrameCodec().apply {
    configureCompression(threshold = 256)
}

val frame = codec.encodeFrame(packetData)
check(codec.decodeFrame(frame).contentEquals(packetData))
```

`MinecraftTransport` attaches the same codec to one Ktor `Socket`. Compression and encryption are activated exactly when
the corresponding protocol packet has crossed the wire:

```kotlin
val transport = MinecraftTransport(socket)

// Immediately after Set Compression has been flushed
transport.configureCompression(threshold = 256)

// Immediately after the Login Encryption Response has been flushed
transport.enableEncryption(sharedSecret)

val packetData = transport.receivePacketData()
transport.sendPacketData(packetData)
transport.close()
```

`transport.frames` is the underlying `MinecraftFrameStream`; construct it directly over any caller-owned
`ByteReadChannel`/`ByteWriteChannel` pair when the connection is not a plain `Socket`.
