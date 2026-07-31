# Protocol symbol-processor guidance

This private build-time module inherits the repository guidance.

- Use KSP only for source-derived generation. Non-source inputs such as official artifacts and reports remain Gradle
  task concerns; a report may be supplied to KSP only to validate source-derived coverage.
- Generate portable model-layer Kotlin without depending on runtime serialization implementation modules.
- Treat processors as deterministic compiler components: report precise symbol errors and rely on compilation plus
  consuming-module tests instead of generator snapshot tests.
- Keep the module unpublished and free of CLI entry points.
