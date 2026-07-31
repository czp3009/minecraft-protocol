# Compression module guidance

This file extends the repository `AGENTS.md`.

- Keep this module independent from Minecraft packets, NBT, transports, filesystems, and platform compression APIs.
- Decode every RFC 1951 block form and enforce output limits before growing buffers or copying matches.
- Deterministic encoding selects the smaller of stored blocks and a portable fixed-Huffman LZ77 stream; wrapper headers
  and checksums remain in their owning modules.
- Test fixed, dynamic, and stored streams against independent JVM zlib behavior plus malformed and truncation matrices.

Run `:compression:jvmTest` while iterating. Shared codec changes also require the protocol-transport and world-format
JVM suites, followed by the applicable standard KMP `allTests` tasks.
