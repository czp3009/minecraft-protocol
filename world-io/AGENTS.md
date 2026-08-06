# world-io

This module owns world paths and filesystem adapters built on `kotlinx.io.files.FileSystem`. Browser-like targets use
the stream modules and do not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Standalone NBT stores compose `nbt-serialization` with compression and filesystem policy; byte grammar remains in the
  serialization module.
- Current paths derive from official resource constants and migration code; historical paths remain explicit API
  variants.
- Region updates commit new external payloads before headers and remove obsolete sidecars after the region commit.
- Timestamp inputs remain explicit, and batch mutations share region work.
- Production writes serialize directly into an atomic temporary-file sink. Every failure, including serialization,
  compression, flushing, closing, and replacement, removes that temporary file when possible and then rethrows the
  original exception; cleanup failures are suppressed on it rather than replacing or wrapping it.

## Tests

Filesystem behavior expressible through `kotlinx.io.files` belongs in `commonTest`. Keep the shared official-server
runner's Host-filesystem namespace restriction explicit in its KDoc. Other JVM-specific filesystem oracles belong in
`jvmTest`.

Run `:world-io:jvmTest` after changes.
