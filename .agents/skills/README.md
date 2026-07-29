# Project skills

These skills form the repository's update workflow. Invoke the narrowest skill that owns the requested change.

| Skill                         | Scope                                                                                                                                                                                                                     |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `minecraft-protocol-modeling` | Network packets, models, MinecraftFormat, vanilla Configuration data, Ktor transport, sessions, authentication, client/server APIs, official-server interoperability, and build-local headless official-client acceptance |
| `minecraft-world-storage`     | Binary NBT, Anvil region containers, compression, dimension paths, filesystem adapters, and official save-file interoperability                                                                                           |
| `minecraft-library-update`    | Release-wide orchestration of both workflows and complete library verification                                                                                                                                            |

All accept no target for the current stable Wiki release, an explicit Minecraft release, or `protocol:<id>`. Use the
same explicit target across skills during one update.

All workflows use the matching official JAR as the primary source and behavioral authority, the revision-pinned Wiki
second, then exact-version MCProtocolLib and Minestom. Nullability follows the same order, falling through to the next
source only when the earlier source is inconclusive.

Gradle tasks own deterministic artifacts under `build/`. Agent-only scratch belongs under `temp/`.
