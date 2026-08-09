# protocol-transport

Kotlin Multiplatform transport primitives for Minecraft Java Edition.

Real TCP targets are JVM, Android, supported Native platforms, Kotlin/JS Node, and Kotlin/WasmJS Node. Browser, D8, and
Wasm/WASI variants are excluded because HTTP-only reachability is insufficient for the Minecraft protocol.

The module provides:

- VarInt21 packet framing;
- Minecraft's zlib compression envelope;
- AES/CFB8 stream encryption;
- framed connections backed directly by Ktor `Socket`, `ByteReadChannel`, and `ByteWriteChannel`.

Zlib delegates to Okio on JVM, Android, and Native and to Kompress's official `kotlinx.io` decorators on JS and WasmJS.
AES/CFB8 delegates to JCA on JVM/Android, cryptography-kotlin on Native, and Node's `crypto` module on JS and WasmJS;
the module contains no compression or AES algorithm implementation.

Its public boundary ends at packet-data bytes. Protocol states and typed packet encoding are implemented by
`protocol-session`.

`MinecraftFrameCodec` and `MinecraftFrameStream` expose caller-owned `kotlinx.io.Source`/`Sink` operations as their
canonical paths, with byte-array overloads as adapters. Memory staging is limited to framing boundaries whose total
encoded length must be known before the body is emitted and to the synchronous-sink/suspending-channel bridge. Malformed
framing, compression, and transport data is exposed through the Okio `IOException` hierarchy.
