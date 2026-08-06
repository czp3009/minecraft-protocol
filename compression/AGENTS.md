# compression

This module owns portable raw RFC 1951 DEFLATE and remains independent of Minecraft packets, NBT, transports,
filesystems, and platform compression APIs.

## Invariants

- Decoding supports stored, fixed-Huffman, and dynamic-Huffman blocks and enforces the output limit before buffer growth
  or match copying.
- Encoding deterministically selects the smaller of stored blocks and the portable fixed-Huffman LZ77 stream.
- Caller-owned `Source` and `Sink` APIs are the canonical paths. Byte-array APIs are adapters over them, and transform
  decorators finalize their own stream without closing the caller's endpoint.
- Wrapper headers, checksums, and compression dispatch remain in their protocol or world-format owners.
- Malformed streams and output-limit failures use `RawDeflateException`, which is an `IOException`; higher layers may
  propagate it directly instead of stacking redundant exception wrappers.

## Verification

Tests cover every block form against independent JVM zlib behavior and include malformed and truncated stream matrices.
Run `:compression:jvmTest`. A shared codec change also requires `:protocol-transport:jvmTest` and
`:world-format:jvmTest`.
