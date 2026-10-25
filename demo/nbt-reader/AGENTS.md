# NBT reader demo

This private CLI demonstrates the `nbt` model and `nbt-serialization` formats on desktop JVM and Native targets.

- Keep the application small: one input file, a typed tree, and optional SNBT output. Reuse the library codecs;
  filesystem access, GZIP detection, and presentation policy belong to the demo.
- Read the named binary root explicitly so inspection retains its name. Require one complete input value and consume
  the GZIP trailer before reporting success.
- Tree limits affect presentation only. SNBT output contains the full root value and follows `SnbtFormat` constraints.
- Standard output is the CLI's data/help channel. Report application failures on standard error with a nonzero exit.
  Keep all authored output, comments, and documentation in English.
- Use Gradle run tasks as the entry points. Do not add packaging or installation tasks.

Run `:demo:nbt-reader:jvmTest`, then the host Native test and run tasks. Keep decoding and rendering scenarios in
`commonTest`; use the fake filesystem for file inputs. Verify configuration-cache reuse after changing run wiring.
