# protocol-vanilla-data

This module owns immutable typed protocol data for the repository-selected Minecraft release: static catalogues,
Configuration registries, feature flags, Known Packs, and tags. Gameplay behavior, world simulation, and general
Datapack interpretation remain outside this module.

## Generation

Root official-analysis tasks provide static reports and complete captures of both Configuration Known Packs branches.
The cacheable generators registered in this module consume those declared artifacts, validate them, and emit functional
`data` packages. They never read the official JAR.

Both complete and Known-Pack-omitted registry branches remain available. Run `:protocol-vanilla-data:jvmTest` after
changing generated-data models, task wiring, or branch selection.
