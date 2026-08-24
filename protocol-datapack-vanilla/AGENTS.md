# protocol-datapack-vanilla agent guide

This module owns generated, immutable vanilla defaults for the repository-selected release: exact official data-pack
archives, static registry and block-state catalogues, both Configuration Known Packs branches, feature flags, tags, and
the convenience path through generic `protocol-datapack` stages. It never reads a filesystem at runtime.

The root `extractOfficialMinecraftDataPacks` task alone extracts core and built-in packs. The cacheable
`generateVanillaDataPackSources` task registered here consumes that declared artifact and emits a small manifest plus
independently loaded batch functions. Do not combine payload strings into an eager property or hand-edit generated
source. Static and Configuration generators likewise consume declared official artifacts rather than the server JAR.

Generic models and transformations belong in `protocol-datapack`; release-specific factories and defaults belong here.
Every convenience API must return the same public generic stage so callers can replace it and continue manually.

Run `./gradlew :protocol-datapack-vanilla:jvmTest` after changes to generated-data models, task wiring, payload loading,
or vanilla branch selection.
