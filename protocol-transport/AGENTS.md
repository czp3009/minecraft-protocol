# protocol-transport

This module owns Minecraft stream framing, the zlib packet envelope, and AES/CFB8 encryption over Ktor byte channels and
sockets. Its public API exposes Ktor socket and channel types.

Packet models, packet IDs, protocol states, authentication policy, and gameplay remain outside this module. Receive
transforms run in decrypt, split-frame, decompress order; send transforms run in compress, frame, encrypt order. Limits
and validation defaults match the selected vanilla network pipeline.

Caller-owned `Source` and `Sink` methods are the canonical framing boundary; byte-array methods delegate to them.
Staging is confined to boundaries that must emit an encoded length first or bridge synchronous sinks to suspending Ktor
channels. Transport failures inherit `kotlinx.io.IOException`, and lower-layer I/O failures are not repeatedly
rewrapped.

`sendPacketDataAndCommit` holds the duplex wire-effect boundary from frame encoding through appending the complete frame
and the caller's non-transport state commit. Receive may wait at that boundary only after obtaining the first encrypted
frame byte, so an idle reader never prevents a transition packet from being sent. Flushing is an independent transport
operation controlled by the caller or Ktor's write channel.

Keep pure frame and cipher algorithms independently testable from sockets. The common real-socket scenario runs on JVM,
Android host, desktop Native, JS Node, and WasmJS Node; Ktor capability reporting excludes runtimes without TCP.
Platform source sets and Gradle filters do not duplicate common public-behavior test entries; they contain only platform
oracles or `actual` support.

Raw compression and AES primitives always come from maintained platform libraries. Shared code owns only Minecraft's
compression envelope, limits, framing, and stream ownership; it does not implement DEFLATE, checksums, AES key
scheduling, S-boxes, or cipher rounds.

Run `:protocol-transport:jvmTest` after changes. Framed-connection changes also require
`:protocol-session:jvmTest`.
