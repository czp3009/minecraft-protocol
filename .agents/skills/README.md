# Optional agent playbooks

These skills teach a coding agent how to carry out repository work that a human can perform directly with the Gradle
commands documented in the root README. They are an optional assistance layer, not part of the project's build,
development, publication, or runtime inputs. Removing `.agents/skills` must not change any Gradle task or library
behavior.

Invoke the narrowest skill that owns the requested change.

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

Skills may invoke Gradle in the same way as a human. Gradle tasks own deterministic artifacts under `build/`; they must
never read skill files or skill-generated output. Agent-only scratch belongs under `temp/`, which Gradle and its helper
scripts must never access.
