# protocol-transport

This module owns Minecraft Java Edition stream framing, zlib compression, and AES/CFB8 encryption over Ktor byte
channels and sockets.

The public API intentionally exposes Ktor socket and channel types. Packet models, packet IDs, protocol states,
authentication policy, and gameplay do not belong here.

Frame limits and validation defaults follow the matching vanilla network pipeline. Stream transforms preserve protocol
order: decrypt, split frame, decompress on receive; compress, frame, encrypt on send.

Keep pure frame and cipher algorithms independently testable from sockets.
