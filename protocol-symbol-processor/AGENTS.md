# protocol-symbol-processor

This private build-time module owns KSP generation derived from Kotlin source annotations.

- Non-source inputs remain Gradle task concerns. An official report may be supplied only to validate source-derived
  coverage.
- Generated model-layer Kotlin remains portable and independent of runtime serialization implementation modules.
- Processor diagnostics identify precise invalid symbols. Compilation and consuming-module tests provide verification;
  generator snapshot tests are not a separate gate.
- The module remains unpublished and has no CLI entry point.

Run the affected consuming module's JVM test task after processor changes.
