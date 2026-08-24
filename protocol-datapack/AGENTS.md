# protocol-datapack agent guide

This module owns vanilla-neutral, filesystem-independent conversion between data-pack resources, Configuration protocol
data, and client runtime registry/tag views. It may depend on `world-format`, `protocol-model`, and `nbt`. It never
depends on `protocol-datapack-vanilla`, reads files, owns sockets, or supplies release-specific defaults.

A disk codec and a network codec are not assumed equivalent. Registry projection therefore requires explicit
caller-supplied projectors. Every public stage remains manually constructible, and generic conversion functions require
their base/default data explicitly.

Client-side APIs represent only data visible in Configuration packets. They must not claim to reconstruct recipes, loot
tables, functions, or other data-pack resources the server did not transmit.

Run `./gradlew :protocol-datapack:jvmTest` after changes.
