# Optional agent playbooks

These skills coordinate repository work for a coding agent through the same source files, `AGENTS.md` rules, Gradle task
graph, and tests used by a human developer. They add no project inputs, generated state, verification tasks, or
alternate development path. Removing `.agents/skills` leaves the project fully buildable and maintainable.

Use the narrowest matching skill:

| Skill                         | Scope                                                                                                                      |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `minecraft-protocol-modeling` | Packets, models, serialization, vanilla data, transport, sessions, auth, client/server APIs, and protocol interoperability |
| `minecraft-world-storage`     | Raw compression, binary NBT, Anvil containers, world paths, filesystem adapters, and save interoperability                 |
| `minecraft-library-update`    | One release-wide update or completeness audit spanning both domains                                                        |

Each skill reads the repository and applicable module `AGENTS.md` files before acting. Release facts, generated data,
task dependencies, platform capabilities, fixture-template selection, and passing state come from source and Gradle
outputs rather than skill prose. Skills use standard test tasks and never add a fixture launcher or manual workspace
policy.
