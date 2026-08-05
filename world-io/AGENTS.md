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

Filesystem behavior expressible through `kotlinx.io.files` belongs in `commonTest`. The shared official-server runner
also remains there; after synchronously closing the server, it uses the documented `minecraft-test-support` Host-path
backdoor to rewrite the server-owned world in place and then restarts the same resource. Its KDoc must state that only
runtimes with filesystem access in the Fixture Host namespace may invoke it. Thin annotated entries belong only in the
standard JVM, Android host, Linux, macOS, and MinGW test source sets. Device and simulator source sets do not contain an
entry, and this capability does not justify a custom source set or a Gradle Fixture flag. Other JVM-specific filesystem
oracles belong in `jvmTest`.

Run `:world-io:jvmTest` after changes.
