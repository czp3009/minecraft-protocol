# protocol-vanilla-data guidance

This module inherits the repository guidance.

- Public APIs expose immutable, typed protocol data rather than storage-format details.
- Static block/entity IDs and synchronized registry snapshots remain bound to the repository's single Minecraft protocol
  version.
- Synchronized registry data comes from the matching official server's actual Configuration packets.
- Complete and Known-Pack-omitted registry branches remain available.
- Generated Kotlin stays in functional `data` packages.
- Gameplay behavior, world simulation, and general Datapack interpretation belong outside this module.
