# World format module guidance

This file extends the repository `AGENTS.md`.

- Keep region container parsing independent from filesystems, decompression, and NBT decoding.
- Derive sector, version, compression, and external-chunk behavior from the exact official server.
- Preserve compressed bytes when callers only inspect or repack a region.
- Reject overlaps, truncation, overflow, invalid versions, checksum failures, and decompression-limit violations.
- Keep custom compression injectable and built-in machinery private.

Run `:world-format:jvmTest` while iterating and `:world-io:jvmTest` after a wire-format change; the latter includes
official world interoperability. Finish with the root `test` gate.
