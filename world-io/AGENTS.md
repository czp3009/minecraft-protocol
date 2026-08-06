# world-io

This module owns world paths and filesystem adapters built on Okio. Browser-like targets use the stream modules and do
not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Standalone NBT stores compose `nbt-serialization` with compression and filesystem policy; byte grammar remains in the
  serialization module.
- Current paths derive from official resource constants and migration code; historical paths remain explicit API
  variants.
- Region updates allocate and write new sectors in place while the old allocation remains reserved. They commit the
  complete header before retiring old sectors, never replace a complete MCA, and preserve the official sidecar order.
- Region timestamps and internal/external selection are storage policy. NBT convenience writes use the configured
  official compression, while raw chunk writes may provide an already-compressed ZLIB, NONE, or LZ4 payload; neither API
  accepts caller-controlled timestamps or external markers.
- Standalone files keep their distinct official policies: level/player NBT use sibling temporary files and backups,
  dimension saved data uses a synced direct write, and player JSON truncates and writes its final path directly.
- System-filesystem world access holds `session.lock` until all owned region stores close. Injectable raw stores do not
  pretend a fake filesystem provides a cross-process lock.

## Tests

Filesystem behavior expressible through Okio or its fake filesystem belongs in `commonTest`. Keep the shared
official-server runner's Host-filesystem namespace restriction explicit in its KDoc. Other JVM-specific filesystem
oracles belong in `jvmTest`.

Run `:world-io:jvmTest` after changes.
