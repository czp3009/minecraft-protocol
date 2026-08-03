# protocol-transport

This module owns Minecraft Java Edition stream framing, zlib compression, and AES/CFB8 encryption over Ktor byte
channels and sockets.

The public API intentionally exposes Ktor socket and channel types. Packet models, packet IDs, protocol states,
authentication policy, and gameplay do not belong here.

Frame limits and validation defaults follow the matching vanilla network pipeline. Stream transforms preserve protocol
order: decrypt, split frame, decompress on receive; compress, frame, encrypt on send.

Keep pure frame and cipher algorithms independently testable from sockets. The real-socket test and its `runTest` entry
belong in `commonTest`; use Ktor's platform capability report to avoid non-Node Wasm runtimes, which do not expose TCP,
while retaining the same common test on JVM, Android Host, Native, and Wasm/Node. Do not duplicate test names in Gradle
filters. Platform test source sets are for platform-specific oracles or `actual` test support, not duplicate
public-behavior test entries.
