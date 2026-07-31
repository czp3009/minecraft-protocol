# protocol-model guidance

This module inherits the repository guidance.

- `model.packet` contains packet payload declarations and their protocol identity annotations.
- `model.type` contains reusable protocol values and sealed wire-shape variants.
- `model.wire` contains declarative hints consumed by binary formats.
- Logical presence and discriminator rules stay with the associated model through Kotlin types, annotations, or logical
  serializers.
- Model code remains valid in common Kotlin source sets.
- Constructor invariants reject states that cannot form valid protocol values.
- New or changed model invariants receive format-independent common tests.
- Packet and data-component identity annotations are source-retained KSP inputs. The private processor validates
  complete coverage and generates portable runtime handoff tables under `build/generated`; keep manual dispatch tables
  out of source.
- Nullable declarations inspect the matching official JAR first. Inconclusive official evidence falls back to the Wiki,
  MCProtocolLib, then Minestom. Semantic conclusions remain in code and tests, not generated specification files.
- `MinecraftProtocol.kt` is generated from the official JAR under `build/generated`; do not add a source-tree copy.
