# nbt

Portable, format-independent Java Edition Named Binary Tag values.

The module provides all 13 NBT tag variants, `NamedNbtTag`, compound-root `NbtDocument`, immutable container snapshots,
and logical serializers that hand raw trees directly to NBT-aware formats. Logical-list behavior matches the selected
official Minecraft release: mixed non-END tags are represented directly, while their physical compound wrappers are not
model state.

`nbt` is independently consumable. Its only production dependency is `kotlinx-serialization-core`; consumers that need
NBT values or raw-tree serializer handoff do not need `nbt-serialization`, any protocol module, or any world module.

Binary grammar, Java modified UTF, root framing, limits, and `kotlinx.io` APIs live in
[`nbt-serialization`](../nbt-serialization/README.md).

```kotlin
val document = NbtDocument(
    NbtCompound(mapOf("DataVersion" to NbtInt(dataVersion))),
)
```
