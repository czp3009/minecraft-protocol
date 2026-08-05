# NBT model extraction and kotlinx.serialization format split

## Status and intent

This plan records the architecture agreed for replacing the current NBT ownership with two clean modules:

- `nbt` is the format-domain model and logical serializer handoff layer;
- `nbt-serialization` is the physical binary NBT and `kotlinx.serialization` format implementation.

Treat that split as the implementation baseline. Public API compatibility is not a constraint because the project is in
early development; do not add deprecated aliases, forwarding type aliases, duplicate packages, or a compatibility module
merely to preserve the current API. "New implementation" means a new public architecture and a clean internal
decomposition. It does not mean discarding already verified binary behavior, security checks, or tests.

The plan also makes the previously misplaced NBT algebra in `protocol-model` part of the new `nbt` module. Protocol-only
types such as `TextComponent` remain in `protocol-model` and depend on the NBT model instead of making NBT depend on the
protocol stack.

The work must preserve these previously settled boundaries:

- byte encoding is serialization, even when a caller supplies a `kotlinx.io.Source` or `Sink`;
- opening files, resolving paths, atomic replacement, and filesystem policy belong to `world-io`;
- Anvil region containers and compression composition belong to `world-format`;
- packet framing, sockets, compression envelopes, and encryption do not move into either NBT module;
- no third `nbt-io` module is planned unless later evidence establishes a real reusable boundary that cannot be
  expressed through caller-owned `Source` and `Sink` values;
- SNBT, Bedrock NBT variants, and compression bundled into the NBT format are outside this change.

The audited repository target is Minecraft `26.2`, reported by `./gradlew -q minecraftVersion` on 2026-08-06. The
worktree was clean when this plan was written. Do not change `MinecraftTarget.MINECRAFT_VERSION` as part of this work.

## Audited repository baseline

### Toolchain and library versions

The current version catalog selects:

- Kotlin `2.4.10`;
- kotlinx.serialization `1.11.0`;
- kotlinx.io `0.9.0`;
- Java toolchain and JVM/Android bytecode target `25` through `BuildVersions`.

`MinecraftFormat` already implements `kotlinx.serialization.BinaryFormat`, so the repository has an established custom
format implementation and testing style. The new NBT format should use the same repository-selected serialization
version and should not introduce a separately versioned serialization runtime.

### Current dependency problem

The current direct dependency graph is effectively:

```text
nbt ----------------------> protocol-model
protocol-serialization ---> protocol-model + nbt
world-format -------------> nbt
world-io -----------------> world-format
```

`nbt/build.gradle.kts` exposes both `protocol-model` and `kotlinx.io` as API dependencies. Consequently a world-only
consumer reaches packet models through `world-format -> nbt -> protocol-model`. That direction is the principal
ownership defect this change must remove.

The existing module packages are:

- current binary NBT: `com.hiczp.minecraft.nbt`;
- NBT value algebra: `com.hiczp.minecraft.protocol.model.type`;
- packet encoding: `com.hiczp.minecraft.protocol.serialization`;
- Anvil format: `com.hiczp.minecraft.world.format`;
- filesystem adapters: `com.hiczp.minecraft.world.io`.

The result is also an API-level split: `NbtDocument` is currently in `com.hiczp.minecraft.nbt`, while its `NbtCompound`
root is in `com.hiczp.minecraft.protocol.model.type`.

### Current NBT model in `protocol-model`

`protocol-model/src/commonMain/kotlin/com/hiczp/minecraft/protocol/model/type/NbtTag.kt` currently contains:

- the `@Serializable` sealed `NbtTag` interface;
- `NbtEnd`;
- `NbtByte`, `NbtShort`, `NbtInt`, `NbtLong`, `NbtFloat`, and `NbtDouble`;
- `NbtByteArray`, `NbtString`, `NbtList`, `NbtCompound`, `NbtIntArray`, and `NbtLongArray`;
- `TextComponent`, which wraps an unnamed protocol NBT value and is not itself an NBT-domain type.

The current tag classes use generated kotlinx serializers and `@SerialName` values such as `byte`, `list`, and
`compound`. Array wrappers implement content equality and hashing. `NbtList` currently requires all entries to have the
same runtime Kotlin class and rejects `NbtEnd` elements. The value containers otherwise expose caller-provided arrays,
lists, and maps directly, so the new model design must explicitly decide whether it guarantees immutable snapshots or
continues to expose mutable backing state.

The model-level test `ProtocolModelContractTest` currently verifies homogeneous, non-END list values. Protocol model
tests also construct NBT values inside packet and data-component invariants.

The NBT references in production `protocol-model` source currently span:

- `packet/ConfigurationPackets.kt`;
- `packet/PlayClientboundStatePackets.kt`;
- `packet/PlayClientboundWorldPackets.kt`;
- `packet/PlayServerboundInteractionPackets.kt`;
- `type/ChunkModels.kt`;
- `type/CommonProtocolModels.kt`;
- `type/ItemPredicateModels.kt`;
- `type/ItemStackModels.kt`;
- `type/ItemWorldValueModels.kt`;
- `type/NbtTag.kt`;
- `type/PlayDisplayModels.kt`;
- `wire/WireAnnotations.kt`.

`NbtEndOptional` and `NetworkNbt` in `WireAnnotations.kt` remain protocol wire hints. They must import the new NBT model
but must not move into `nbt`, because their meaning is specific to the Minecraft packet format. Custom logical
serializers in `PlayDisplayModels.kt` call `NbtTag.serializer()` directly and therefore form an important migration and
serializer-handoff test.

`TextComponent` has substantially more protocol consumers than the raw NBT declarations. Move it to its own
protocol-model source file without changing its protocol ownership; only its import of `NbtTag`/`NbtString` changes.

### Current binary format

`nbt/src/commonMain/kotlin/com/hiczp/minecraft/nbt/NbtBinaryFormat.kt` currently combines public format API, model
wrappers, stream access, binary grammar, validation, modified UTF, and resource accounting in one file.

Its public declarations are:

- `NbtBinaryFormatConfiguration`;
- `NamedNbtTag`;
- `NbtDocument`;
- `NbtFormatException`;
- `NbtBinaryFormat`.

The format supports:

- unnamed values through `encodeTag`/`decodeTag`;
- named values through `encodeNamedTag`/`decodeNamedTag`;
- named compound-root documents through `encodeDocument`/`decodeDocument`;
- corresponding byte-array convenience methods;
- caller-owned `Source` and `Sink` instances;
- full-consumption checking for byte arrays;
- exactly-one-value consumption for stream decoders.

The current limits are:

| Configuration property  | Current default   |
|-------------------------|-------------------|
| `maximumDepth`          | `512`             |
| `maximumCollectionSize` | `1_048_576`       |
| `maximumByteArraySize`  | `16 * 1_048_576`  |
| `maximumStringBytes`    | `65_535`          |
| `maximumEncodedBytes`   | `64L * 1_048_576` |

The codec writes big-endian numeric payloads, implements Java modified UTF, recognizes tag IDs `0..12`, prohibits named
`TAG_End`, requires a compound document root, writes an empty list with `TAG_End` as its element type, prohibits
`TAG_End` compound values, rejects negative/oversized lengths, and applies depth and byte accounting before untrusted
allocation where the current implementation has enough information.

The current `NbtBinaryFormatTest` suite is an executable behavior specification. It covers:

- round trips for every tag kind in a named document;
- Java modified UTF including NUL and supplementary characters;
- exact stream consumption and trailing byte-array rejection;
- unknown tags, truncation, invalid lengths, and invalid list element IDs;
- depth, collection, byte-array, string, and encoded-byte limits on reads and writes;
- named `TAG_End`, non-compound documents, and invalid writer structures;
- malformed modified-UTF sequences;
- allocation checks before declared primitive arrays are created;
- deterministic randomized nested round trips;
- every truncated prefix of every tag kind;
- independent byte-array and collection limits;
- exact-limit acceptance, including the 65,535-byte modified-UTF boundary.

Port or replace every one of those assertions before removing the old format. The new public API may be different; the
wire, malformed-input, and resource-safety coverage may not disappear.

### Current protocol integration

`protocol-serialization` exposes `protocol-model` as API and uses `nbt` as an implementation dependency. Its internal
`NbtBinaryCodec` adapts packet buffers to the current unnamed NBT methods and translates `NbtFormatException` into
`MinecraftSerializationException`. `MinecraftFormatConfiguration` forwards NBT depth and general collection/byte-array
limits into that adapter.

`MinecraftEncoder` currently recognizes NBT from the runtime value. `MinecraftDecoder` recognizes it from descriptor
serial names. Both contain the hard-coded string
`com.hiczp.minecraft.protocol.model.type.NbtTag`; the decoder also checks the concrete `compound` descriptor for
`@NetworkNbt`. This package-sensitive recognition must be replaced as part of the migration, not merely updated to a new
string.

Packet behaviors that must remain intact include:

- unnamed network NBT;
- `@NetworkNbt` on properties declared as concrete tag subtypes;
- `@NbtEndOptional` as the packet null sentinel;
- rejection when a packet field declared as `NbtCompound` receives another tag type;
- packet-local byte consumption rather than consuming the rest of a containing payload;
- protocol configuration limits and exception translation;
- official codec-oracle agreement for packets containing NBT.

### Current world integration

`world-format` exposes `nbt` as an API dependency. `RegionChunkNbtFormat` composes decompression with
`NbtBinaryFormat.decodeDocumentFromByteArray` and performs the inverse operation for region chunks. It does not open
files and should continue to own this composition.

`world-io` exposes `world-format`. `NbtFileStore` opens paths, reads/writes bytes, applies standalone NONE/GZIP/ZLIB
compression through the world compression registry, and atomically replaces files. `WorldRegionStore` composes region
containers with chunk NBT. These filesystem responsibilities do not move into either NBT module.

The existing official world interoperability scenario generates a world with the exact selected server, stops it, reads
and rewrites `level.dat` and all region kinds in place, restarts the server, and requires a successful load/save. It is
the final interoperability gate for this refactor, but it does not replace lower-layer tests.

## Evidence policy and official 26.2 baseline

### Precedence

Use repository evidence in the fixed order from the root guide:

1. the selected Minecraft `26.2` official server JAR and executable behavior;
2. the revision-matched Minecraft Wiki only for facts the JAR does not expose;
3. exact-version MCProtocolLib;
4. exact-version Minestom.

The knbt project is an implementation-design reference only. It is not evidence for Mojang wire behavior and does not
change that precedence.

The available Gradle-produced official analysis currently contains target, packets, registries, and Configuration data,
but no standalone NBT report. The verified official artifacts are under
`build/protocol-reference/26.2/mojang-server/`, including the original `server.jar` and extracted runtime. These are
build outputs, never source inputs to committed code.

If signatures and existing executable scenarios are insufficient, add or extend a narrowly scoped official behavior
probe through the repository's official-analysis/Fixture Host architecture. Read `buildSrc/AGENTS.md` before doing so.
Do not add an ad hoc Gradle decompiler, check in decompiled source, hand-copy deterministic values, or make ordinary
modules inspect the official JAR.

### Official class inventory already established

The selected runtime contains the named classes under `net.minecraft.nbt`, including all tag classes, `Tag`,
`TagType`, `TagTypes`, `NbtIo`, `NbtAccounter`, `NbtOps`, visitors, and SNBT classes.

Public signature inspection established the following current facts:

- `Tag` defines the binary IDs `TAG_END = 0` through `TAG_LONG_ARRAY = 12` and `MAX_DEPTH = 512`;
- `NbtAccounter` exposes `DEFAULT_NBT_QUOTA = 2_097_152`, `UNCOMPRESSED_NBT_QUOTA = 104_857_600`, and a default maximum
  stack depth of `512`;
- `NbtIo` has distinct APIs for compressed compound documents, ordinary compound documents, any tags, and unnamed tags:
  `read`/`write`, `readAnyTag`/`writeAnyTag`, and `readUnnamedTag`/`writeUnnamedTag`;
- `NbtIo` also exposes `writeUnnamedTagWithFallback`, whose current purpose and exact output behavior must be determined
  before deciding whether this library needs an equivalent;
- `CompoundTag` owns named-entry read/write behavior and uses string keys;
- `StringTag` writes through `DataOutput`, consistent with the repository's already-tested Java modified-UTF contract;
- official `CompoundTag.putBoolean` and `Tag.asBoolean` provide evidence for Boolean-as-byte semantics at the NBT model
  boundary;
- current `ListTag` contains `wrapIfNeeded`, `wrapElement`, `tryUnwrap`, `addAndUnwrap`, an empty-string wrapper marker,
  and `identifyRawElementType`.

The current `ListTag` signatures are especially important. They suggest that 26.2 may expose logically mixed list values
by wrapping elements into homogeneous compound payloads on the wire. The current project model rejects mixed lists
outright. Do not preserve or remove that invariant from memory: establish the official construction, in-memory, write,
read, and unwrap behavior with an executable probe first. Distinguish the binary rule that one list payload has one tag
ID from the current official logical API's possible wrapper behavior.

### Required official behavior matrix

Before finalizing the new model and root API, capture tests or an evidence note for:

- the bytes and accepted root types for every `NbtIo` named/any/unnamed read/write pair;
- whether ordinary document methods require a compound and what happens to the root name;
- the exact role of `writeUnnamedTagWithFallback`;
- Java modified-UTF behavior for NUL, BMP, supplementary characters, malformed input, and the unsigned-short limit;
- all tag IDs, big-endian primitive values, arrays, empty compounds, and empty lists;
- permitted empty-list element type IDs and the canonical type emitted by the official writer;
- 26.2 mixed-list wrapping and unwrapping behavior;
- duplicate compound key behavior;
- `TAG_End` at root, as a named value, in compounds, and in lists;
- negative and overflowing lengths, unknown tag IDs, truncation, and exception categories;
- official accounting order, depth behavior, and quota boundaries before allocation;
- whether a stream read consumes exactly one value and leaves following bytes unread;
- official world-file and packet examples for named and unnamed forms.

Official behavior determines the binary and NBT-domain rules. Mapping arbitrary Kotlin serializers onto NBT where Mojang
has no Kotlin equivalent is library policy; make those policy decisions explicit rather than presenting them as official
facts.

## knbt reference audit

The permitted reference is [BenWoodworth/knbt](https://github.com/BenWoodworth/knbt). A shallow research checkout was
audited at commit `ff915657d956eb1438355e40d14dccbeb870a9d8` (2025-11-19, version
`0.11.10-SNAPSHOT`) under `temp/knbt-reference`. It is an agent-only reference checkout, is not a Gradle input, and must
not be committed.

That snapshot uses Kotlin `2.2.21`, kotlinx.serialization `1.9.0`, Okio `3.16.2`, and the LGPL-3.0 license. The project
under implementation uses newer Kotlin and serialization versions and a different stream library.

### Patterns worth adapting

The following design patterns are useful references:

- a top-level NBT format implementing `BinaryFormat`;
- `encodeToNbtTag` and `decodeFromNbtTag` tree APIs alongside byte-array APIs;
- caller-owned stream APIs;
- public format-specific `NbtEncoder`/`NbtDecoder` hooks for custom serializers;
- specialized handling for `ByteArray`, `IntArray`, `LongArray`, and raw `NbtTag` values;
- classes and string-keyed maps represented by compounds, and lists represented by `TAG_List`;
- a tree reader/writer separated from a binary reader/writer;
- format configuration for defaults, unknown keys, serializers modules, root naming, and polymorphic discriminators;
- custom serializers for `NbtTag` that hand raw tree nodes directly to an NBT-aware encoder/decoder;
- path-aware decoding errors and explicit polymorphic discriminator collision checks.

Relevant upstream source anchors include:

- [
  `Nbt.kt`](https://github.com/BenWoodworth/knbt/blob/ff915657d956eb1438355e40d14dccbeb870a9d8/src/commonMain/kotlin/Nbt.kt);
- [
  `NbtFormat.kt`](https://github.com/BenWoodworth/knbt/blob/ff915657d956eb1438355e40d14dccbeb870a9d8/src/commonMain/kotlin/NbtFormat.kt);
- [
  `NbtEncoder.kt`](https://github.com/BenWoodworth/knbt/blob/ff915657d956eb1438355e40d14dccbeb870a9d8/src/commonMain/kotlin/NbtEncoder.kt);
- [
  `NbtDecoder.kt`](https://github.com/BenWoodworth/knbt/blob/ff915657d956eb1438355e40d14dccbeb870a9d8/src/commonMain/kotlin/NbtDecoder.kt);
- [
  `NbtTagSerializers.kt`](https://github.com/BenWoodworth/knbt/blob/ff915657d956eb1438355e40d14dccbeb870a9d8/src/commonMain/kotlin/NbtTagSerializers.kt).

### Choices that must not be copied blindly

- knbt is LGPL-3.0. Use it to understand architecture and edge cases; do not copy source text into this repository
  without a deliberate license decision and required notices. Prefer an independent implementation driven by this
  repository's tests and official behavior.
- knbt uses Okio. This project standardizes on `kotlinx.io`; do not add Okio.
- the audited knbt Java binary source/sink uses ordinary UTF-8 byte conversion. This repository has a settled and tested
  Java modified-UTF requirement from the official implementation. Preserve the official behavior.
- knbt bundles NBT compression and Java/Bedrock/Bedrock-network variants. Compression remains in `world-format`, and
  Bedrock variants are outside this repository's current scope.
- the audited binary writer models a compound-with-one-entry root and derives class root names from `@SerialName`.
  Minecraft 26.2 exposes more explicit any/unnamed APIs; root behavior must follow the official evidence matrix rather
  than knbt's public convention.
- the audited `decodeFromByteArray` path does not provide this repository's explicit trailing-input contract. Keep full
  consumption checks.
- knbt does not supply the current depth, collection, byte-array, modified-UTF, total-byte, and pre-allocation contract.
  Preserve or strengthen the repository checks.
- knbt currently rejects `Char` and enum values, maps Booleans to strict `TAG_Byte` 0/1, and has its own null and
  polymorphism behavior. Treat these as possible policy examples, not automatically selected semantics.
- knbt uses experimental/internal kotlinx.serialization surfaces, including polymorphic internals. The upstream docs
  explicitly warn that `BinaryFormat`, `Encoder`, and `Decoder` are not stable for third-party inheritance. Implement
  against repository version `1.11.0`, isolate opt-ins, and add compatibility tests rather than copying a 1.9.0
  implementation.

The official kotlinx.serialization references for this implementation are the
[
`BinaryFormat` API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-binary-format/),
the [
`Encoder` API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization.encoding/-encoder/),
and their matching 1.11.0 decoder/composite contracts.

## Target architecture

### Module ownership

| Module                   | Target ownership                                                                                      |
|--------------------------|-------------------------------------------------------------------------------------------------------|
| `nbt`                    | NBT value algebra, NBT-domain wrappers, intrinsic invariants, logical raw-tag serializer handoff      |
| `nbt-serialization`      | kotlinx.serialization format, tree encoder/decoder, binary grammar, limits, modified UTF, Source/Sink |
| `protocol-model`         | packet payloads, protocol-only values such as `TextComponent`, and packet wire hints                  |
| `protocol-serialization` | Minecraft packet encoding and its adapter to unnamed `nbt-serialization`                              |
| `world-format`           | Anvil containers, compression dispatch, and region-payload/NBT composition                            |
| `world-io`               | paths, `FileSystem`, standalone NBT files, region stores, and atomic filesystem behavior              |

The target package roots are:

```text
nbt/src/...                    com.hiczp.minecraft.nbt
nbt-serialization/src/...      com.hiczp.minecraft.nbt.serialization
protocol-model/src/...         com.hiczp.minecraft.protocol.model...
protocol-serialization/src/... com.hiczp.minecraft.protocol.serialization...
```

Use those roots consistently in every source set, including `commonTest` and any platform-specific tests. Prefer an IDE
package refactor/file move when available, followed by repository-wide `rg` checks; do not rely on the IDE refactor as
the only completeness proof.

### Target direct dependencies

```text
nbt
  -> kotlinx-serialization-core

nbt-serialization
  -> nbt
  -> kotlinx-serialization-core
  -> kotlinx-io-core

protocol-model
  -> nbt
  -> kotlinx-serialization-core

protocol-serialization
  -> protocol-model
  -> nbt-serialization (implementation)

world-format
  -> nbt
  -> nbt-serialization
  -> compression (implementation)
  -> kotlinx-io-core

world-io
  -> nbt
  -> nbt-serialization
  -> world-format
  -> kotlinx-io-core
```

Use `api` for types that occur in a module's public signatures and a direct dependency when the module imports or
exposes the type itself. Do not rely on an accidental transitive dependency merely because another API module currently
exports it. The essential direction is always `protocol-model -> nbt`, never `nbt -> protocol-model`.

### `nbt` contents

The new core module owns:

- the complete NBT tag algebra;
- `NamedNbtTag` and `NbtDocument`, provided the official root investigation confirms their final semantics;
- tag/domain invariants such as legal END placement and the final 26.2 list model;
- content equality/hash behavior and any immutable snapshot policy;
- the logical `KSerializer<NbtTag>` handoff required by packet model serializers;
- only the smallest public serializer bridge needed to avoid a dependency cycle.

It must not import or expose:

- `ByteArray` as an encoded document representation (array tag payload values are naturally still `ByteArray`);
- `kotlinx.io.Buffer`, `Source`, or `Sink`;
- byte order, type IDs, modified UTF, encoded-byte limits, or trailing-input behavior;
- compression, paths, filesystems, sockets, packet registries, or Anvil types.

`nbt` may apply the Kotlin serialization plugin and expose `kotlinx-serialization-core`, just as `protocol-model` owns
logical serializers without owning physical packet bytes. A preferred no-cycle raw-tag handoff is:

- a minimal `NbtTagEncoder` contract that can accept an `NbtTag` tree;
- a minimal `NbtTagDecoder` contract that can return an `NbtTag` tree;
- `NbtTag.serializer()` using those contracts rather than serializing the sealed hierarchy as an artificial
  discriminator/value object;
- the physical `NbtEncoder`/`NbtDecoder` and the protocol encoder/decoder implementing the contracts in their owning
  modules.

Those bridge contracts must expose no buffers or binary operations. If a 1.11.0 serialization constraint makes this
shape unworkable, record the alternative and its tests before implementation; `nbt` still may not depend on
`nbt-serialization`, and hard-coded package-name recognition is not an acceptable long-term fallback.

### `nbt-serialization` contents

The new module owns:

- a true `BinaryFormat` implementation, provisionally named `NbtFormat`;
- `NbtFormatConfiguration` and format-specific `SerializationException` subclasses;
- public NBT-aware encoder/decoder interfaces for format-specific custom serializers where justified;
- `encodeToNbtTag` and `decodeFromNbtTag`;
- arbitrary `KSerializer<T>` to/from NBT tree conversion;
- NBT tree to/from binary encoding;
- named, any-root, and unnamed entry points selected by the official root behavior design;
- Java modified UTF, big-endian primitives, tag IDs, and END terminators;
- depth, collection, string, byte-array, total-byte, and pre-allocation checks;
- caller-owned `Source`/`Sink` adapters and byte-array convenience functions;
- full-consumption checking for byte arrays and exactly-one-value stream consumption.

The initial implementation should be tree-first:

```text
KSerializer<T> <-> NbtTag tree <-> binary NBT
```

This separates Kotlin serialization policy from the wire grammar and lets both halves be tested independently. Direct
serializer-to-stream encoding is a later optimization only after profiling demonstrates a material need. It may not be
introduced at the cost of bypassing tree semantics, limit checks, or raw-tag support.

The module does not open, close, or discover external resources. Public stream methods leave ownership with the caller.
It does not compress NBT bytes; region and standalone-file callers continue to compose compression in their current
owners.

### Protocol and world ownership after the split

- `TextComponent` remains in `protocol-model` and imports `NbtTag`/`NbtString` from `nbt`.
- Packet declarations and other protocol values continue to expose NBT types where the protocol genuinely carries NBT.
- `NetworkNbt` and `NbtEndOptional` remain protocol-model wire hints.
- `protocol-serialization` delegates unnamed tag bytes to `nbt-serialization` without duplicating the grammar.
- `RegionChunkNbtFormat` continues to combine region decompression with named/document NBT.
- `NbtFileStore` continues to combine path I/O, compression, and named/document NBT.
- Anvil code does not move. Anvil is the outer `.mca` container; decompressed chunk payloads happen to be binary NBT.

## Pre-implementation API and policy gates

The module split is settled, but the following choices were deliberately not invented during the architecture
discussion. Resolve each from official behavior where applicable, then record library-policy choices in tests and KDoc
before implementing the broad migration.

### Root framing

Define unambiguous APIs for:

- a pure `NbtTag` tree with no binary root framing;
- a named tag;
- an ordinary compound document;
- an unnamed network tag;
- the official any-tag form if distinct from the preceding forms.

`BinaryFormat.encodeToByteArray` has only a serializer and a value, so its root framing must be explicit in the format
instance/configuration or in a documented single convention. Do not silently infer incompatible wire framing from the
runtime value type. Decide whether a typed root name is explicit, configured, derived from `@SerialName`, or
unsupported; official world-file behavior takes precedence over knbt's root-class naming convention.

### Kotlin-to-NBT semantic matrix

The implementation must explicitly decide and test every row:

| Kotlin serializer shape  | Candidate NBT representation                                | Required decision/evidence                                |
|--------------------------|-------------------------------------------------------------|-----------------------------------------------------------|
| `Byte`                   | `TAG_Byte`                                                  | settled                                                   |
| `Short`                  | `TAG_Short`                                                 | settled                                                   |
| `Int`                    | `TAG_Int`                                                   | settled                                                   |
| `Long`                   | `TAG_Long`                                                  | settled                                                   |
| `Float`                  | `TAG_Float`                                                 | settled                                                   |
| `Double`                 | `TAG_Double`                                                | settled                                                   |
| `String`                 | `TAG_String`                                                | settled; binary text is Java modified UTF                 |
| `Boolean`                | strict `TAG_Byte` `0`/`1`                                   | strongly supported by official API; confirm strict policy |
| `ByteArray`              | `TAG_Byte_Array`                                            | settled                                                   |
| `IntArray`               | `TAG_Int_Array`                                             | settled                                                   |
| `LongArray`              | `TAG_Long_Array`                                            | settled                                                   |
| other primitive arrays   | list or unsupported                                         | library policy                                            |
| class/object             | `TAG_Compound` using descriptor element names/`@SerialName` | settled shape; defaults/null policy remains               |
| `Map<String, T>`         | `TAG_Compound`                                              | settled shape                                             |
| map with non-string keys | reject                                                      | preferred; NBT compound names are strings                 |
| `List<T>`                | `TAG_List`                                                  | enforce encoded homogeneity; decide empty/mixed behavior  |
| `Char`                   | string or unsupported                                       | prefer explicit rejection unless a contract is accepted   |
| enum                     | serial name string, ordinal, or unsupported                 | library policy; never silently choose ordinal             |
| nullable value           | omitted field, explicit policy, or rejection                | NBT has no null tag; root/list/compound cases differ      |
| sealed/open polymorphism | compound discriminator or rejection                         | library policy and collision handling                     |
| inline/value class       | underlying serialized representation                        | verify kotlinx 1.11 behavior                              |
| raw `NbtTag`             | direct tree handoff                                         | required                                                  |

Null handling deserves a dedicated design test. If null compound properties are omitted, decoding must define when a
missing nullable field becomes null versus a missing required field error. Null roots and null list entries cannot be
quietly represented. Model this as an explicit configuration if more than one behavior is supported.

List handling also needs two separate contracts:

- the binary element type and length fields must follow official wire behavior;
- the public NBT algebra must decide whether it models only raw homogeneous binary lists or the official 26.2 logical
  wrapping/unwrapping behavior.

Do not use runtime Kotlin class equality as the final encoded-homogeneity test if different serializers can produce the
same NBT tag type. Conversely, do not accept a serializer list whose encoded elements produce different tag IDs unless
the official wrapping policy is intentionally implemented.

### Format configuration

At minimum, design and test:

- serializers module;
- root/framing mode;
- `encodeDefaults`;
- unknown-key behavior;
- strict Boolean decoding if Boolean support is selected;
- polymorphic discriminator only if polymorphism is supported;
- maximum depth;
- maximum collection/compound entries;
- maximum byte-array size;
- maximum modified-UTF byte length, never above 65,535;
- maximum total encoded/decoded bytes;
- any separate limits needed to prevent tree construction from bypassing binary limits.

Do not hard-code protocol-specific configuration into this format. `MinecraftFormatConfiguration` adapts its limits to
the NBT configuration at the protocol boundary.

## Detailed implementation sequence

Each phase is a coherent, buildable batch. Do not delete the existing binary implementation or tests before its
replacement passes the corresponding phase gate.

### Phase 0: evidence lock and migration inventory

1. Reconfirm the selected release with `./gradlew -q minecraftVersion` and preserve `26.2`.
2. Re-read the root and affected-module `AGENTS.md` files. Read `buildSrc/AGENTS.md` only if an official probe or
   fixture bridge changes build logic.
3. Run the current focused JVM baseline before edits:
   `:nbt:jvmTest`, `:protocol-model:jvmTest`, `:protocol-serialization:jvmTest`, `:world-format:jvmTest`, and
   `:world-io:jvmTest`.
4. Complete the official behavior matrix above with executable evidence. Prefer the existing official runtime and
   Fixture Host. Keep optional manual notes under `temp/`.
5. Record the final root, list, END, modified-UTF, and accounting decisions in tests or KDoc before restructuring their
   implementation.
6. Generate a complete import/reference inventory with `rg`, including production, tests, generated-source consumers,
   and non-obvious descriptor-string comparisons.
7. Confirm there are no uncommitted user changes overlapping the migration. Preserve unrelated changes if the worktree
   is no longer clean.

Phase gate: official wire behavior is no longer an open assumption, and every current consumer has a target owner.

### Phase 1: create the target module graph and extract the model

1. Add `:nbt-serialization` to `settings.gradle.kts`.
2. Create `nbt-serialization/build.gradle.kts` with the same supported KMP target set as portable NBT today, the Kotlin
   serialization plugin, Android namespace `com.hiczp.minecraft.nbt.serialization`, API dependencies on `nbt`,
   kotlinx.serialization core, and kotlinx.io core, plus common tests.
3. Repurpose `nbt/build.gradle.kts` as a pure model module: apply the Kotlin serialization plugin, expose
   kotlinx.serialization core, and remove both `protocol-model` and kotlinx.io.
4. Move the tag algebra from protocol-model into
   `nbt/src/commonMain/kotlin/com/hiczp/minecraft/nbt/` using package refactoring.
5. Split `TextComponent` into a protocol-model-owned file and import the new NBT package.
6. Move `NamedNbtTag` and `NbtDocument` into `nbt` after applying the official root decisions. Keep
   `NbtBinaryFormatConfiguration`, binary exceptions, tag IDs, and byte helpers out of the model.
7. Move model-only tests, especially list/END invariants and content equality, from protocol-model/current NBT tests
   into
   `nbt/commonTest`.
8. Add `api(project(":nbt"))` to `protocol-model` and update all listed production imports and tests.
9. Introduce the minimal raw-tag serializer bridge and update `NbtTag.serializer()` so downstream logical serializers
   keep working without a reverse dependency.
10. Keep the old binary implementation temporarily compilable in `nbt-serialization` or behind a short-lived internal
    migration seam; do not publish both old and new public APIs.

Phase gate:

- `nbt` compiles without protocol-model or kotlinx.io;
- `protocol-model` compiles against `nbt`;
- `TextComponent` remains protocol-owned;
- model tests pass;
- no source under `nbt` imports a protocol, world, buffer, or filesystem package.

### Phase 2: implement the tree serialization format

1. Add format/configuration/error types under `com.hiczp.minecraft.nbt.serialization`.
2. Make the public format a real `BinaryFormat`, but implement and test tree conversion independently first.
3. Implement `NbtEncoder`, `CompositeNbtEncoder`, `NbtDecoder`, and `CompositeNbtDecoder` against kotlinx.serialization
   `1.11.0`; isolate all experimental/internal opt-ins in the smallest implementation files.
4. Implement `encodeToNbtTag` and `decodeFromNbtTag`.
5. Implement the settled primitive, array, class, map, list, raw-tag, default, unknown-field, null, enum, inline, and
   polymorphism policies.
6. Enforce string map keys and encoded list homogeneity with path-aware format exceptions.
7. Implement raw `NbtTag` serializers through the bridge rather than allowing an artificial sealed-class wrapper on the
   NBT wire.
8. Verify contextual and polymorphic serializers through the supplied `SerializersModule` where supported.
9. Add tree-only tests that never touch `ByteArray`, `Source`, or `Sink`.

Tree test matrix:

- every primitive and specialized array;
- nested data classes and `@SerialName`;
- empty and non-empty classes, maps, compounds, and lists;
- defaults enabled/disabled;
- unknown keys strict/ignored;
- missing required fields and the selected null policy;
- Boolean boundaries and invalid Boolean bytes at the tree boundary if applicable;
- enum/Char policy;
- inline/value classes;
- raw tags at root and nested positions;
- serializers-module contextual values;
- sealed/open polymorphism and discriminator collisions if supported;
- heterogeneous encoded list rejection or official wrapper behavior;
- error paths for nested failures.

Phase gate: arbitrary supported `@Serializable` values round-trip through an `NbtTag` tree, and raw tag round trips do
not depend on protocol code or binary encoding.

### Phase 3: implement and attach binary NBT

1. Split the current monolithic codec into focused internal components: tag-type mapping, modified UTF, bounded input,
   bounded output, tree reader, and tree writer as appropriate.
2. Reuse independently written and already tested algorithms where they still match official evidence. Do not retain old
   public methods merely because their internal code is reused.
3. Implement the official named/document/any/unnamed root forms with unambiguous public entry points.
4. Make `BinaryFormat.encodeToByteArray` and `decodeFromByteArray` delegate through the selected root convention and the
   tree codec.
5. Add caller-owned `Source`/`Sink` overloads. Never close a caller-owned source or sink.
6. Apply depth, collection, byte-array, string, total-byte, and pre-allocation limits before untrusted work. Ensure tree
   decoding does not bypass those limits.
7. Preserve byte-array full-consumption rejection and stream exactly-one-value consumption.
8. Translate EOF, malformed structures, unsupported serializer shapes, and resource limits into format-specific
   subclasses of `SerializationException` with useful nested paths and causes.
9. Port every current `NbtBinaryFormatTest` case into `nbt-serialization/commonTest` and add official-root/list cases
   discovered in Phase 0.
10. Add differential fixtures where the official JAR writes bytes decoded by the library and the library writes bytes
    read by the official JAR.

Binary test matrix:

- golden bytes for all 13 tag IDs and both numeric signs/extremes;
- Java modified UTF for NUL, BMP, surrogate pairs, malformed continuations, and exact maximum length;
- named, document, any, and unnamed roots;
- official empty-list and mixed-list behavior;
- duplicate compound names and END placement;
- all truncated prefixes and unknown IDs;
- negative, overflowing, and configured-limit lengths;
- depth/quota boundaries and allocation-before-read attacks;
- exact total-byte limits on both encode and decode;
- randomized bounded nested trees;
- stream concatenation and byte-array trailing bytes;
- caller ownership of Source/Sink;
- official cross-read/cross-write fixtures.

Phase gate: `:nbt-serialization:jvmTest` passes independently, including official differential coverage, and the old
binary public implementation can be removed.

### Phase 4: migrate protocol-model and protocol-serialization completely

1. Finish all production/test imports from `com.hiczp.minecraft.protocol.model.type.Nbt*` to
   `com.hiczp.minecraft.nbt.Nbt*`.
2. Keep `TextComponent`, packet models, custom packet serializers, and wire hints in protocol-model.
3. Replace hard-coded NBT descriptor serial-name checks in `MinecraftEncoder`/`MinecraftDecoder` with the raw-tag bridge
   or another typed mechanism established in Phase 1.
4. Replace `NbtBinaryCodec` internals with the unnamed mode from `nbt-serialization`.
5. Preserve packet-local consumption. Avoid the current unnecessary copy of every remaining packet byte if the new
   Source/reader adapter can consume exactly one tag safely; optimize only without weakening limits.
6. Preserve translation to `MinecraftSerializationException` and propagation of protocol NBT depth/size limits.
7. Verify `@NetworkNbt` for `NbtTag` and concrete tag subtypes and `@NbtEndOptional` for null/non-null values.
8. Update protocol samples, serializer modules, fixture generators, and every packet test import.
9. Run the official packet codec oracle for NBT-bearing packets and the ordinary official-server scenario.

Downstream source that must be included in the import audit extends beyond the two protocol modules. Current references
also exist in protocol-client/server tests and `protocol-vanilla-data`; update them without changing their functional
ownership.

Phase gate: no protocol code contains the former package name, packet NBT remains unnamed and bounded, and official
packet fixtures still agree.

### Phase 5: migrate world-format and world-io

1. Add explicit target API dependencies on `nbt` and `nbt-serialization` wherever public signatures expose their types.
2. Update `RegionChunkNbtFormat` to use the new named/document binary API while preserving independent compression
   injection and decompressed-size limits.
3. Update `NbtFileStore` to use the new named/document binary API while preserving NONE/GZIP/ZLIB composition,
   compressed/decompressed limits, caller-selected filesystem, write mutex, and atomic replacement.
4. Update `WorldRegionStore`, world tests, and official runner imports.
5. Keep region parsing usable without decoding or inflating chunk payloads.
6. Run all vanilla region compression round trips and standalone file tests.
7. Run the official generate/rewrite/reload scenario after the lower layers pass.

Phase gate: world-format has no filesystem behavior, world-io owns all path access, and the official server successfully
reloads the library-rewritten world.

### Phase 6: documentation, package audit, and cleanup

Update all architecture documentation in the same coherent change:

- root `AGENTS.md`: `nbt` owns the NBT model; `nbt-serialization` owns physical binary NBT and the kotlinx format;
- `nbt/AGENTS.md`: model-only ownership, intrinsic invariants, and `:nbt:jvmTest`;
- new `nbt-serialization/AGENTS.md`: binary, limits, stream ownership, official evidence, and downstream verification;
- `protocol-model/AGENTS.md`: shared NBT is an API dependency rather than owned source;
- `protocol-serialization/AGENTS.md`: unnamed packet NBT delegates to `nbt-serialization`;
- `world-format/AGENTS.md` and `world-io/AGENTS.md`: retain their composition/filesystem boundaries;
- `.agents/skills/minecraft-world-storage/references/workflow.md`: update dependency order and focused tasks;
- module READMEs and root README/module listing where applicable.

Then perform structural audits:

- no production or test import of `com.hiczp.minecraft.protocol.model.type.Nbt*` remains;
- `nbt` has no `protocol`, `world`, `kotlinx.io`, filesystem, compression, or socket dependency;
- `nbt-serialization` has no `FileSystem`, `Path`, Ktor, packet, Anvil, or compression dependency;
- `protocol-model` contains no NBT tag declarations;
- each source set uses its owning module's package root;
- no hard-coded old descriptor/package string remains;
- no duplicate legacy public type or forwarding alias remains;
- Gradle dependency reports show no cycle and no world-to-protocol leakage.

Phase gate: documentation and physical layout describe the same architecture, and the tracked diff contains no stale
package artifacts.

## Verification queue

### Focused JVM progression

Run the platform-native wrapper on Windows:

```powershell
.\gradlew.bat :nbt:jvmTest
.\gradlew.bat :nbt-serialization:jvmTest
.\gradlew.bat :protocol-model:jvmTest
.\gradlew.bat :protocol-serialization:jvmTest
.\gradlew.bat :world-format:jvmTest
.\gradlew.bat :world-io:jvmTest
```

After each coherent shared layer is stable, run its downstream suffix rather than waiting until the end. A binary NBT
change requires at least protocol-serialization, world-format, and world-io tests.

Then run the repository-wide JVM gate defined by the root guide:

```powershell
.\gradlew.bat :minecraft-test-fixture-host:test jvmTest
```

This also catches protocol-client, protocol-server, protocol-vanilla-data, and other downstream package/import
consumers.

### Official gates

Required official verification is cumulative:

1. focused standalone NBT cross-read/cross-write evidence against the 26.2 JAR;
2. official packet codec-oracle verification for NBT-bearing packets;
3. official server protocol interoperability;
4. official world generate/rewrite/reload through `world-io`.

A successful world reload does not replace malformed-input, tree-serialization, or packet tests.

### Portable targets

After JVM stability, run applicable standard JS/Node, Wasm/D8, Android host, and Native tasks or `allTests`. Tree NBT,
binary NBT, in-memory region composition, and compression remain portable. Do not add browser-driver infrastructure.

Use `clean` or `--rerun-tasks` only when stale output makes forced verification necessary, and keep the build cache
enabled.

### Final static checks

Before handoff:

- run `git diff --check`;
- inspect `git status --short` and preserve unrelated user changes;
- use `rg` for old packages, old module/API names, hard-coded descriptor names, and forbidden imports;
- inspect the final Gradle dependency graph for `nbt`, `nbt-serialization`, protocol-model, protocol-serialization,
  world-format, and world-io;
- confirm no generated Kotlin, official evidence, or `temp/knbt-reference` files are tracked.

## Concrete migration map

| Current location/type                                                                 | Target                                                                            |
|---------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `protocol-model/.../type/NbtTag.kt` tag declarations                                  | `nbt/.../com/hiczp/minecraft/nbt/`                                                |
| `TextComponent` in the same file                                                      | separate protocol-model-owned type file                                           |
| model list invariant test in `ProtocolModelContractTest`                              | `nbt/commonTest`, with protocol uses remaining in protocol-model tests            |
| `NamedNbtTag` and `NbtDocument` in current `NbtBinaryFormat.kt`                       | `nbt` domain model files after official root design                               |
| `NbtBinaryFormatConfiguration` and binary errors                                      | `nbt-serialization`, redesigned around the new format                             |
| raw binary reader/writer and modified UTF                                             | internal `nbt-serialization` components                                           |
| current `NbtBinaryFormatTest`                                                         | split between `nbt` model tests and `nbt-serialization` tree/binary tests         |
| `protocol-serialization/internal/NbtBinaryCodec.kt`                                   | adapter to unnamed `nbt-serialization`; retain only protocol-specific translation |
| hard-coded NBT descriptor names in Minecraft encoder/decoder                          | typed raw-tag serializer bridge                                                   |
| `RegionChunkNbtFormat`                                                                | same owner, new NBT APIs/direct dependencies                                      |
| `NbtFileStore` and `WorldRegionStore`                                                 | same owner, new NBT APIs/direct dependencies                                      |
| root and module AGENTS/README statements that say protocol-model owns the NBT algebra | updated two-module architecture                                                   |

Do not create a second checked-in copy of any moved declaration. The package refactor and downstream import update are
one migration, not a compatibility period.

## Risks and controls

### Experimental serialization inheritance

`BinaryFormat`, `Encoder`, and `Decoder` are explicitly not stable for third-party inheritance. Keep implementation
opt-ins narrow, avoid kotlinx internal APIs when public descriptor/encoder APIs suffice, and lock behavior with project
version `1.11.0` tests. Do not add knbt as a dependency merely to outsource this risk.

### Model/format dependency cycle

Raw NBT fields need a serializer that can hand a tree to both the NBT and Minecraft formats. The minimal logical bridge
must live upstream without exposing bytes. Any design that makes `nbt` depend on `nbt-serialization` or protocol-model
is rejected.

### Root ambiguity

Named documents and unnamed packet tags are not interchangeable. Explicitly test bytes and prevent an apparently
convenient generic API from silently choosing the wrong framing.

### Mixed-list behavior in 26.2

The selected official `ListTag` has wrapper/unwrapper machinery absent from the current model. Resolve it before
freezing the new core API. The answer may affect model invariants, tree serialization, and binary canonicalization.

### Java modified UTF

Do not replace the existing implementation with Kotlin/Okio UTF-8 helpers. NUL and supplementary characters have
different byte sequences under Java modified UTF, and the encoded-byte limit is an unsigned short.

### Untrusted input and tree allocation

A clean encoder/decoder abstraction can accidentally allocate an entire declared collection before checking limits. Keep
bounded binary input below tree construction and ensure decoder collection sizes are validated before creating lists,
maps, or primitive arrays.

### Stream ownership and consumption

Closing caller streams or consuming the remainder of a packet/concatenated stream would be a regression. Test ownership,
exactly-one-value consumption, and byte-array full consumption separately.

### Tree-first performance

Tree-first conversion allocates an intermediate tree. Accept that for the initial correctness-focused implementation. Do
not introduce a direct streaming path without benchmark evidence and semantic parity tests.

### Third-party license and semantic drift

knbt is LGPL-3.0 and implements a broader, differently configured format. Keep the pinned commit in the plan for
traceability, independently implement the required behavior, and use official/JVM evidence for wire decisions.

## Completion criteria

The plan is complete only when all of the following are true:

- `nbt` is a standalone NBT model/logical-serializer module under `com.hiczp.minecraft.nbt`;
- `nbt-serialization` exists under `com.hiczp.minecraft.nbt.serialization` and implements a true kotlinx
  `BinaryFormat` plus NBT tree conversion;
- the NBT tag algebra and its intrinsic tests no longer live in protocol-model;
- `TextComponent`, packet wire hints, and other protocol-only types remain in protocol-model;
- dependency direction is `protocol-model -> nbt`, with no world-to-protocol leakage;
- named/document/any/unnamed root semantics match the selected official JAR and are unambiguous in the API;
- every supported Kotlin serializer shape has documented and tested NBT semantics;
- every current binary valid/malformed/limit test has an equivalent or stronger replacement;
- packet NBT still passes ordinary and official codec tests;
- world-format remains filesystem-independent and world-io retains filesystem ownership;
- the official 26.2 server loads and saves the library-rewritten world;
- portable target tests pass;
- root/module documentation and the storage workflow describe the new architecture;
- no legacy packages, hard-coded descriptor names, compatibility aliases, generated evidence, tracked third-party
  checkout, or stale package artifacts remain.
