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
- Nullable declarations inspect the matching official JAR first. Inconclusive official evidence falls back to the Wiki,
  MCProtocolLib, then Minestom. Results remain recorded in project specification state.
