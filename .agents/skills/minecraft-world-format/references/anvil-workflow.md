# Anvil format workflow

## Trace the official container

Inspect the selected official `RegionFile`, region storage/cache callers, and compression-version registry. Establish
from code and emitted files:

- region/chunk coordinate conversion and local index calculation;
- location and timestamp table layout;
- sector size, reserved header area, allocation and reuse rules;
- chunk length accounting and whether the compression byte is inside that length;
- compression identifier bits and external-payload marker;
- inline capacity and externalization threshold;
- sidecar payload semantics independent of its filesystem path;
- treatment of missing, zero, overlapping, truncated, excessive, and stale entries.

Do not encode these as remembered constants in the skill. Recheck the selected official implementation whenever the
release or affected format changes.

## Trace compression

For each registered official compression form, identify the numeric ID, outer stream/container, raw algorithm, checksum,
termination, and malformed-input behavior. Distinguish the legacy Minecraft/LZ4 library block stream from the standard
LZ4 frame format when the official implementation does.

Keep the registry extensible only to the degree exposed by the public API. Require an explicit codec for a custom
identifier and report what official interoperability cannot validate for that extension.

Do not impose a policy-sized decompressed-output ceiling. Transfer incrementally when the format does not require a
known length, and do not stage a whole stream merely to discover a size. Do not close caller-owned endpoints when
closing a compression decorator is required to finish or validate its own stream.

## Compose NBT payloads

Use `minecraft-nbt` for tag algebra and binary root rules. This module composes a caller-supplied `NbtDocument` with one
region compression; it does not own domain-specific chunk/entity/POI schemas or choose filesystem placement. Preserve
unknown NBT fields when the public composition API promises round-trip retention.

## Verify layers

Use pure in-memory tests for header parsing, allocation, record round trips, compression matrices, external markers, and
malformed containers. Use the `world-io` standard tests only for filesystem placement and official server reload. A
successful official reload does not replace precise format failure tests.
