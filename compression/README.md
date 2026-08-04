# compression

Portable raw RFC 1951 DEFLATE for Kotlin Multiplatform.

`RawDeflate.decode` accepts stored, fixed-Huffman, and dynamic-Huffman blocks and requires an explicit maximum output
size. `RawDeflate.encode` deterministically selects a stored or fixed-Huffman LZ77 representation.

```kotlin
val compressed = RawDeflate.encode(input)
val decoded = RawDeflate.decode(compressed, maximumOutputBytes = input.size)
```

This module handles raw DEFLATE only. Zlib/gzip wrappers, checksums, Minecraft packet envelopes, and region compression
dispatch remain in their owning modules.
