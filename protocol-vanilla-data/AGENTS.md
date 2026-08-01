# protocol-vanilla-data guidance

This module inherits the repository guidance.

- Public APIs expose immutable, typed protocol data rather than storage-format details.
- Static block/entity IDs and synchronized registry snapshots remain bound to the repository's single Minecraft protocol
  version.
- Synchronized registry data comes from the matching official server's actual Configuration packets.
- Complete and Known-Pack-omitted registry branches remain available.
- Generated Kotlin stays under `build/generated` in functional `data` packages and is included in published source JARs.
  Do not commit generated payload source.
- Root official-analysis tasks capture JAR-derived reports and both Configuration branches as complete data under the
  root `build/generated/official-minecraft/<version>/` tree.
- Register Configuration and static-data source generators only in this module. They consume Gradle artifacts containing
  official-analysis JSON, never the official JAR, and validate the data before publishing generated source.
- Gameplay behavior, world simulation, and general Datapack interpretation belong outside this module.
