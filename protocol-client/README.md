# protocol-client

A Kotlin Multiplatform Minecraft Java Edition protocol client.

The public API connects with Ktor TCP, exposes the underlying `Socket`, and supports:

- Status request and ping;
- offline Login and Configuration;
- online Login when supplied a session service and cryptography provider;
- extensible cookie, plugin, Known Packs, and Configuration packet hooks;
- automatic chunk/biome decode context derived from synchronized registries;
- a typed Play-ready result while retaining the live `MinecraftSession`.
