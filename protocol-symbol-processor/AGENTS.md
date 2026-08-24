# protocol-symbol-processor

This private JVM module owns KSP generation derived from Kotlin source annotations.

- Non-source generation remains in Gradle tasks. An official report may be supplied only to validate source-derived
  coverage.
- Generated model-layer Kotlin stays portable and independent of runtime serialization implementations.
- Diagnostics identify the precise invalid symbol. Compilation and consuming-module tests are the verification path; do
  not add a separate CLI or snapshot gate.
- Keep this module unpublished.

Run the affected consuming module's JVM test task after processor changes.
