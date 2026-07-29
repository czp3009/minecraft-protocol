# nbt

Portable binary Named Binary Tag encoding and decoding over
`kotlinx.io.Source` and `Sink`.

`NbtBinaryFormat` supports unnamed packet values, named values, compound-root documents, byte-array conveniences, Java
modified UTF, and configurable depth, allocation, string, and total-byte limits. Byte-array decoders reject trailing
input; stream decoders consume exactly one value.

The NBT value algebra remains in `protocol-model` because packet models and world files share it.

```kotlin
val document = NbtDocument(
    root = NbtCompound(mapOf("DataVersion" to NbtInt(dataVersion))),
)
val bytes = NbtBinaryFormat.encodeDocumentToByteArray(document)
val decoded = NbtBinaryFormat.decodeDocumentFromByteArray(bytes)
```

Run `.\gradlew.bat :nbt:nbtLayerTest` for the focused suite.
