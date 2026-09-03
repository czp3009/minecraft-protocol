# protocol-symbol-processor

This private JVM module owns KSP generation derived from Kotlin source annotations.

- Non-source generation remains in Gradle tasks. An official report may be supplied only to validate source-derived
  coverage.
- The supplied `packets.json` contains packet state, direction, protocol ID, and resource identity, not official Java
  class names or payload members. Never reinterpret a resource identity as a class/member name or claim that report
  coverage establishes the packet schema; `protocol-model` owns the manual or agent-assisted official class and field
  audit.
- Generated model-layer Kotlin stays portable and independent of runtime serialization implementations.
- Diagnostics identify the precise invalid symbol. Compilation and consuming-module tests are the verification path; do
  not add a separate CLI or snapshot gate.
- Keep this module unpublished.

Run the affected consuming module's JVM test task after processor changes.
