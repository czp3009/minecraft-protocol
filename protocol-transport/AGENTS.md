# protocol-transport

This module owns Ktor socket transport, Minecraft framing, the zlib packet envelope, and AES/CFB8 stream encryption.

## Local invariants

- Receive order is decrypt, split frame, decompress; send order is compress, frame, encrypt.
- Caller-owned `Source` and `Sink` methods are the canonical framing API. Byte-array helpers delegate to them; stage
  only where a prefixed length or suspending channel bridge requires it.
- Transport failures use the `kotlinx.io.IOException` hierarchy. Do not repeatedly wrap lower-layer I/O failures.
- `sendPacketDataAndCommit` keeps frame append and the caller's state commit in one ordered wire-effect boundary. An
  idle reader may block that boundary only after it has consumed the first encrypted frame byte.
- Flushing remains an explicit, independent operation.
- Keep frame and cipher algorithms testable without sockets. Raw zlib and AES implementations come from maintained
  platform libraries; shared code owns only Minecraft framing, envelope semantics, validation, and ownership.
- Packet types, IDs, state machines, and authentication policy remain outside this module.

## Verification

Run `:protocol-transport:jvmTest`. Framed connection changes also require `:protocol-session:jvmTest`.
