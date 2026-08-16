# nbt

Portable, format-independent Java Edition Named Binary Tag values.

The module provides all NBT tag variants, `NamedNbtTag`, compound-root `NbtDocument`, immutable container snapshots, and
logical serializers that hand raw trees directly to NBT-aware formats. Logical-list behavior matches the
project-selected Minecraft release: mixed non-END tags are represented directly, while their physical compound wrappers
are not model state.

```kotlin
val document = NbtDocument(
    NbtCompound(mapOf("DataVersion" to NbtInt(dataVersion))),
)
```

Binary grammar, Java modified UTF, root framing, limits, and `kotlinx.io` APIs live in
[`nbt-serialization`](../nbt-serialization/README.md).
