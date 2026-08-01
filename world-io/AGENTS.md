# World I/O module guidance

This file extends the repository `AGENTS.md`.

- Use `kotlinx.io.files.FileSystem`; keep Java and platform filesystem APIs out of common production code.
- Keep browser-like targets in the stream modules instead of adding a partial filesystem implementation here.
- Resolve current world-storage paths from official resource constants and migration code; retain legacy access only
  through explicit API variants.
- Commit external payloads before region headers and remove stale sidecars after the region commit.
- Keep timestamps explicit and batch region mutations when possible.

Run `:world-io:jvmTest` after changes; it includes official world interoperability. Finish with the applicable standard
KMP platform tasks or `allTests`. Filesystem behavior expressible with `kotlinx.io.files` belongs in `commonTest`;
official-process tests shared by JVM and desktop Native belong in `externalProcessTest`, while only genuinely
JVM-specific checks belong in `jvmTest`.
