# nbt

Portable, format-independent Java Edition Named Binary Tag values.

The module provides all NBT tag variants, `NamedNbtTag`, compound-root `NbtDocument`, immutable container snapshots, and
logical serializers that hand raw trees directly to NBT-aware formats. Logical-list behavior matches the
project-selected Minecraft release: mixed non-END tags are represented directly, while their physical compound wrappers
are not model state.

## Building and reading values

Containers and arrays wrap ordinary Kotlin collections and snapshot them during construction; application code composes
new values instead of mutating existing ones:

```kotlin
val root = NbtCompound(
    mapOf(
        "DataVersion" to NbtInt(dataVersion),
        "LevelName" to NbtString("New name"),
        "SpawnPos" to NbtList(listOf(NbtInt(x), NbtInt(y), NbtInt(z))),
        "Biomes" to NbtIntArray(biomeIds),
    ),
)

val document = NbtDocument(root)
val namedRoot = NamedNbtTag("", root)

val version = (root["DataVersion"] as NbtInt).value
root.forEachEntry { name, tag ->
    // Visits entries in insertion order without copying the compound
}
val editable = root.value // Defensive copy for further composition
```

`size`, indexed access, and iteration read the immutable snapshot without copying, while `value` returns a defensive
copy. Lists may mix any non-END element types, and containers reject `NbtEnd` values.

## Logical serializer handoff

Every tag is serializable through an `NbtTagSerializer`, so serializable models can embed raw tags. An NBT-aware format
receives the complete tree through `NbtTagEncoder`/`NbtTagDecoder` instead of visiting each entry; a format that cannot
encode raw NBT rejects the value explicitly instead of guessing a representation:

```kotlin
@Serializable
data class LevelMetadata(
    val data: NbtCompound,
)
```

Binary grammar, Java modified UTF, root framing, limits, and `kotlinx.io` APIs live in
[`nbt-serialization`](../nbt-serialization/README.md).
