# compression

Portable raw RFC 1951 DEFLATE for Kotlin Multiplatform.

`RawDeflate.decodeToSink` accepts stored, fixed-Huffman, and dynamic-Huffman blocks and requires an explicit maximum
output size. `RawDeflate.encodeToSink` deterministically selects a stored or fixed-Huffman LZ77 representation while
copying between caller-owned streams.

```kotlin
RawDeflate.encodeToSink(source, compressedSink)
RawDeflate.decodeToSink(compressedSource, decodedSink, maximumOutputBytes)

// In-memory adapters over the same paths:
val compressed = RawDeflate.encode(input)
val decoded = RawDeflate.decode(compressed, maximumOutputBytes = input.size)
```

Transform decorators are also available for composition. Closing a compressing decorator finalizes its DEFLATE stream
but never closes the caller-owned downstream sink.

This module handles raw DEFLATE only. Zlib/gzip wrappers, checksums, Minecraft packet envelopes, and region compression
dispatch remain in their owning modules.
