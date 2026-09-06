# protocol-symbol-processor

This private JVM module owns KSP generation derived from Kotlin source annotations.

- Non-source generation remains in Gradle tasks. Consume the declared root `officialMinecraftPackets` class/member
  artifact to validate source coverage, official class/nesting and ordered field names. Never inspect official JARs here
  or derive Java names from resource identities.
- Require documented `PacketInfo` exceptions for intentional name/shape differences. Shared official classes may have
  repeated state registrations and explicit per-registration serializers. Preserve complete route identity in dispatch.
- Field-name checks do not establish nested types, nullability, producer/consumer semantics or bytes; protocol-model
  owns that review and protocol-serialization owns physical compatibility tests.
- Generated model-layer Kotlin stays portable and independent of runtime serialization implementations.
- Diagnostics identify the precise invalid symbol. Compilation and consuming-module tests are the verification path; do
  not add a separate CLI or snapshot gate.
- Keep this module unpublished.

Run the affected consuming module's JVM test task after processor changes.
