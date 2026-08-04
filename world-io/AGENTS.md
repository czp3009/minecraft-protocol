# world-io

This module owns world paths and filesystem adapters built on `kotlinx.io.files.FileSystem`. Browser-like targets use
the stream modules and do not receive a partial filesystem implementation.

## Invariants

- Common production code contains no Java or platform filesystem APIs.
- Current paths derive from official resource constants and migration code; historical paths remain explicit API
  variants.
- Region updates commit new external payloads before headers and remove obsolete sidecars after the region commit.
- Timestamp inputs remain explicit, and batch mutations share region work.

## Tests

Filesystem behavior expressible through `kotlinx.io.files` belongs in `commonTest`. The JVM, Android host, and desktop
Native official-server scenario also belongs in `commonTest`; it transfers bounded world snapshots through
`minecraft-test-support` and never opens a Fixture Host path. Only JVM-specific filesystem oracles belong in `jvmTest`.

Run `:world-io:jvmTest` after changes.
