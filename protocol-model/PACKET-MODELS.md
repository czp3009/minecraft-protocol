# Packet model boundaries and official counterparts

Packet identities come from the selected release's report. The separate `officialMinecraftPackets` artifact follows
the official registration and `PacketType` declarations to Java classes, superclasses and ordered members. KSP checks
the source inventory against that evidence; it does not derive class names from resource paths.

The model audit compares the official packet's producer, reader/writer or `STREAM_CODEC`, and consumer. Physical
compatibility is exercised separately by the portable exact-byte tests and the official codec fixtures. These checks
cover different properties: registration and field-name agreement alone cannot prove nullability or conditional bytes.

## Declarations and ownership

| Area                                          | Model decision and reason                                                                                                                                                                                                                       |
|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Registered packet classes                     | Keep official simple names, direction and nesting. State-shared official classes have repeated registrations on one Kotlin value                                                                                                                |
| Known-pack selection and test-instance status | Preserve `ClientboundSelectKnownPacks`, `ServerboundSelectKnownPacks` and `ClientboundTestInstanceBlockStatus`, including the absence of a `Packet` suffix                                                                                      |
| Movement packet subclasses                    | Keep `ClientboundMoveEntityPacket.Pos/PosRot/Rot` and `ServerboundMovePlayerPacket.Pos/PosRot/Rot/StatusOnly`. Constructor fields contain transmitted members; fixed discriminator flags and unused base-class members derive from the subclass |
| Packet-owned records                          | Chunk biome data, command suggestions, attribute snapshots, recipe entries, player-info entries, team parameters and game-rule entries are nested under their official packet owner                                                             |
| Packet-owned actions                          | Resource packs, client commands, player actions/commands, chat completions, player-info actions, waypoints and test-instance actions use their official nested names                                                                            |
| Runtime callbacks/builders                    | Command resolvers, packet handlers, registry access, entity/level references and operation callbacks are omitted; only their transmitted values cross the model boundary                                                                        |
| Shared protocol values                        | Coordinates, profiles, text, components, command arguments, metadata, registry mappings and other values used across packets stay in `model.type`                                                                                               |

The packet package contains the wire-facing values, not world data. `Chunk`, `EntityChunk`, `PoiChunk`, their mutable
properties and semantic IDs live in `world-format`. `protocol-world` converts between the two representations using
explicit contexts. Neither model package owns a game loop or performs gameplay calculations.

## Representation differences

| Official shape                                   | Kotlin representation and scope of the difference                                                                                                                                                          |
|--------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Primitive arrays / owned byte buffers            | Immutable `ByteString` for owned opaque bytes; content equality for array-bearing values. No Netty buffer ownership or I/O escapes into models                                                             |
| Runtime registry holders and keys                | Explicit registry IDs/identifiers, or reference/direct sealed holders when the wire supports both. Resolution belongs to the supplied `PacketCodecContext`                                                 |
| `ClientIntent`                                   | Three valid intentions with explicit IDs 1–3. The reserved zero has no model enum entry and decoding rejects it                                                                                            |
| Declaration order differs from wire order        | Public members follow the official declarations; private serialization proxies preserve the actual reader/writer order for command trees, particles, experience, signs, block actions and item interaction |
| Compound bit flags                               | Typed flag values or logical Boolean members preserve the same bit fields. Movement flags are shared across movement variants; unused flags are not independently writable state                           |
| Commands: entries/stubs/flags                    | `CommandNode` and `CommandParser` form a logical tree description without Brigadier builders, runtime argument objects or redundant nullable stub fields                                                   |
| Boss-event operations                            | `BossBarAction` is a sealed operation family. Each operation carries only its own fields; the serializer owns its discriminator and flag packing                                                           |
| Objective/team operations                        | `ObjectiveUpdate` and `TeamUpdate` exclude impossible method/payload combinations. Team `Parameters` retains the official field names/order; its operation serializer writes the wire order                |
| Player-info action mask and entries              | `PlayerInfoUpdatePayload` couples the mask to the nested entries. Only selected fields are serialized. `PlayerListProfile` carries the profile body; the entry's `profileId` is the one transmitted UUID   |
| Look-at / entity interaction / seen advancements | Sealed variants express conditional payloads without unrelated nullable fields or a contradictory discriminator                                                                                            |
| Section block changes                            | `SectionBlockChange` couples local position and state instead of accepting parallel arrays of unrelated lengths                                                                                            |
| Stop-sound presence flags                        | One `StopSound` value represents the four source/sound presence combinations                                                                                                                               |
| Abilities and spawn information                  | Shared logical aggregates preserve the reusable abilities and player-spawn facts while excluding runtime player/level objects                                                                              |
| Configuration registry records, tags and links   | Shared logical records carry identifier/payload lists without official mutable registry synchronization owners or map wrappers                                                                             |
| Status                                           | Shared `ServerStatus` and its nested records are model values. The JSON string is their physical packet representation                                                                                     |

`@PacketInfo.shapeException` records each top-level aggregate difference beside the declaration. These are scoped
representation decisions; the domain model's omission of runtime ownership is not a reason to shorten official packet
names or erase their direction.

## Chunk and state-dependent codecs

`ClientboundLevelChunkPacketData` contains heightmaps, one contiguous Section `buffer: ByteString`, and its nested
`BlockEntityInfo` records. It requires no dimension layout. `BlockEntityInfo` uses Byte/Short values for the exact
transmitted widths, while its official in-process container uses Ints. Optional update tags remain nullable.
`ClientboundLightUpdatePacketData` contains masks and light updates. The low-level `LevelChunkSectionData` seam belongs
to the physical Section format; it is not the complete packet's terrain model.

`ClientboundShowDialogPacket` has a direct NBT codec in Configuration and a registry-holder codec in Play. The repeated
registrations explicitly select those serializers. A state-shared packet must be encoded with its route/state; guessing
the state from a Kotlin class would lose this distinction. Session dispatch captures the incoming state before applying
transitions and uses it for state-sensitive payload lifting.

NBT saved records and packet update tags have different contents. Merchant/container/recipe packets describe existing
wire schemas; they do not make the library choose prices, recipes, inventory rules or block-entity property bindings.

## Verification scope

KSP validates every registered route against the declared class/member artifact. Serialization tests exercise exact
bytes and conditional variants, with official codec fixtures for the matching release. Official client/server tests
exercise negotiation, initial world data, entity pairing, respawn and reconfiguration. User simulation tests separately
exercise disk/domain/packet conversion and untransmitted server state. A passing sample does not establish arbitrary
mod compatibility or a lossless reconstruction of data absent from the network representation.
