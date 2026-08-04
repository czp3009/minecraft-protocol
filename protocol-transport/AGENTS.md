# protocol-transport

This module owns Minecraft stream framing, the zlib packet envelope, and AES/CFB8 encryption over Ktor byte channels and
sockets. Its public API exposes Ktor socket and channel types.

Packet models, packet IDs, protocol states, authentication policy, and gameplay remain outside this module. Receive
transforms run in decrypt, split-frame, decompress order; send transforms run in compress, frame, encrypt order. Limits
and validation defaults match the selected vanilla network pipeline.

Keep pure frame and cipher algorithms independently testable from sockets. The common real-socket scenario runs on JVM,
Android host, desktop Native, and Wasm/Node; Ktor capability reporting excludes runtimes without TCP. Platform source
sets and Gradle filters do not duplicate common public-behavior test entries; they contain only platform oracles or
`actual` support.

Run `:protocol-transport:jvmTest` after changes. Framed-connection changes also require
`:protocol-session:jvmTest`.
