# protocol-transport

Kotlin Multiplatform transport primitives for Minecraft Java Edition.

The module provides:

- VarInt21 packet framing;
- Minecraft's zlib compression envelope;
- AES/CFB8 stream encryption;
- framed connections backed directly by Ktor `Socket`, `ByteReadChannel`, and `ByteWriteChannel`.

Its public boundary ends at packet-data bytes. Protocol states and typed packet encoding are implemented by
`protocol-session`.

`MinecraftFrameCodec` and `MinecraftFrameStream` expose caller-owned `Source`/`Sink` operations as their canonical
paths, with byte-array overloads as adapters. Memory staging is limited to framing boundaries whose total encoded length
must be known before the body is emitted and to the synchronous-sink/suspending-channel bridge.
