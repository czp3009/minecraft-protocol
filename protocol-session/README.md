# protocol-session

Typed Minecraft packet sessions over `protocol-transport`.

`MinecraftSession`:

- maps packet IDs through `MinecraftPacketRegistry`;
- validates clientbound/serverbound direction and connection state;
- performs Handshake, Login, Configuration, and Play transitions;
- activates compression immediately after `SetCompressionPacket`;
- supports the legacy unframed server-list ping.

The caller retains direct access to the underlying Ktor-backed frame stream.
