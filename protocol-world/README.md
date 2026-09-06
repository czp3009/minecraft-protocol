# protocol-world

Converts mutable [`world-format`](../world-format/README.md) Chunks, Entities and ItemStacks to and from Play values.
It has no filesystem, connection lifecycle, gameplay or vanilla registry singleton.

## Chunk packet conversion

### Plain API

Construct a `ChunkPacketEncoder` or `ChunkPacketDecoder` once for the dimension and connection epoch where its inputs
stay stable. This example spells out both contexts. `chunkContext` is an explicit construction input independent of
the Chunk being encoded. After client/server `negotiate()`, take `minecraftDimensionContext` from its result,
call `chunkContext(defaultBlockState, defaultBiome)` with application defaults, and use its `packetCodecContext` for
registry mappings. Construct those defaults as `BlockState(BlockId("minecraft:air"))` and `BiomeId("minecraft:plains")`
when they suit the application. For a standalone vanilla
example, [the vanilla provider](../protocol-configuration-vanilla/README.md#contexts-for-a-vanilla-overworld) supplies
the matching dimension and registry data.

The application's `blockEntityUpdateTag` receives each `BlockEntity` from the encoded Chunk and returns its selected
`NbtCompound` update tag, or null. `sectionStatistic` receives the Chunk, absolute Section Y and requested
`SectionStatistic` and returns a count. It is called for missing `NON_EMPTY_BLOCKS` or `FLUIDS` counts, including
Sections with no terrain. Saved Sections contain no such runtime
counts. This example uses known stored heightmap values and omits unavailable light; override the other provider
callbacks if your application needs to calculate those values too.

```kotlin
fun createChunkPacketEncoder(
    chunkContext: ChunkContext,
    packetCodecContext: PacketCodecContext,
    blockEntityUpdateTag: (BlockEntity) -> NbtCompound?,
    sectionStatistic: (Chunk, Int, SectionStatistic) -> Int,
): ChunkPacketEncoder = ChunkPacketEncoder(
    ChunkPacketEncoderContext(
        chunkLayout = chunkContext.dimensionTypeLayout.chunkLayout,
        hasSkyLight = chunkContext.dimensionTypeLayout.hasSkyLight,
        defaultBlockState = chunkContext.defaultBlockState,
        defaultBiome = chunkContext.defaultBiome,
        packetCodecContext = packetCodecContext,
        chunkPacketWriteMappings = ChunkPacketWriteMappings(blockEntityUpdateTag),
        chunkPacketRequiredDataProvider = ChunkPacketRequiredDataProvider.RequirePresent.copy(
            sectionStatistic = sectionStatistic,
        ),
    ),
)

fun createChunkPacketDecoder(
    chunkContext: ChunkContext,
    packetCodecContext: PacketCodecContext,
): ChunkPacketDecoder = ChunkPacketDecoder(
    ChunkPacketDecoderContext(
        chunkContext = chunkContext,
        packetCodecContext = packetCodecContext,
        chunkPacketReadMappings = ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
        chunkPacketMissingDataProvider = {
            ChunkPacketMissingData(status = "minecraft:full", inhabitedTime = 0, isLightCorrect = false)
        },
    ),
)
```

`PacketCodecContext` is in `com.hiczp.minecraft.protocol.model.type`; `NbtCompound` is in `com.hiczp.minecraft.nbt`;
domain types and NBT property mappings are in `com.hiczp.minecraft.world.format`. The remaining codec types are in
this module's `com.hiczp.minecraft.protocol.world` package.

The encoder uses stored statistics when present and calls the provider only for missing values. It never updates the
input. A null light layer is unavailable; a present all-zero layer sends known darkness.

The decoder calls its missing-data provider once using the packet's position and preserves the supplied mutable
references. The short `ChunkPacketMissingData` constructor above explicitly chooses local status/time/light flags,
fresh empty collections and unknown caches. These are application choices, not recovered server values. Unknown
private fields remain absent. Writing the result to disk requires a separately configured NBT encoder and persistence
metadata; a received packet does not reconstruct a server's full saved Chunk.

Obtain `chunk` by constructing `Chunk(ChunkPosition(x, z), chunkContext)` or reading `ChunkNbtDecodeResult.chunk`
through world-io.
With the codecs above, `chunkPacketEncoder.encode(chunk)` produces a `ClientboundLevelChunkWithLightPacket`; pass that
returned packet to `chunkPacketDecoder.decode(...)` to construct a new Chunk. The
[complete flow](../world-io/README.md#disk-to-memory-to-packets) includes reading and modifying the server Chunk first.

### Convenience API

The `to...` extensions delegate to the same codecs. This helper takes a Chunk obtained as described above and codecs
from the two construction examples; the returned Chunk is the client projection:

```kotlin
fun projectChunk(
    chunk: Chunk,
    chunkPacketEncoder: ChunkPacketEncoder,
    chunkPacketDecoder: ChunkPacketDecoder,
): Chunk = chunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoder).toChunk(chunkPacketDecoder)
```

Each extension also accepts the complete directional context instead of a prebuilt codec for a one-off conversion.
To derive those contexts from negotiation results, use the endpoint factories documented in
[protocol-client](../protocol-client/README.md#decode-chunk-packets) and
[protocol-server](../protocol-server/README.md#convert-semantic-chunks-to-packets). Neither path infers configuration
from the Chunk being converted or performs I/O.

### Block entities and inventory packets

`blockEntityUpdateTag` selects only client-visible fields from the current Block Entity. Never substitute complete
saved properties for that selection. A plain chest with no update-tag data can return `null`; this does not omit its
position or type from the packet. Other Block Entity types may require their own tags.

A full Chunk packet does not carry a chest's inventory. Reading it back creates the Block Entity without `Items`;
wrapping that received object does not recover the server inventory. An opened menu sends
`ClientboundContainerSetContentPacket`, followed by updates such as `ClientboundContainerSetSlotPacket`. Applications
use `ItemStackPacketEncoder`/`ItemStackPacketDecoder` with explicit component mappings to convert the items, and own the
menu's slot layout and state.
The [user simulation test](../world-io/src/commonTest/kotlin/com/hiczp/minecraft/world/io/UserWorldSimulationTest.kt)
demonstrates both paths, including physical packet encoding and decoding.

The internal Section byte format is owned by [`protocol-serialization`](../protocol-serialization/README.md).
Full packet payload encoding requires neither the dimension nor a Section count.

## Entities and items

`EntityPacketEncoder` reads an Entity and per-operation `EntityPairingData`, returning one finite pairing sequence.
The current passenger graph selects the UUIDs resolved through the supplied connection ID map. Its mappings project
spawn data, metadata, attributes and equipment from the same semantic properties; missing head yaw and passenger facts
have explicit providers. The type-specific `ClientboundAddEntityPacket.data` field has explicit read/write mappings;
its raw integer is never installed as a built-in Entity property. `EntityPacketDecoder` accepts exactly one leading
spawn and its trailing messages. It applies
the configured data mappings in order and returns unresolved relation messages, other entity IDs and unrecognized tails
in `EntityPacketDecodeResult.pendingPackets`. The endpoint registers objects, resolves relations and enqueues bundles.

The `EntityPacketReadMappings.fromProperties(...)` and `EntityPacketWriteMappings.fromProperties(...)` factories map
semantic attributes and equipment. Attribute defaults, sync selection and the persistence flag absent from received
modifiers are supplied explicitly. Their constructors accept individual projections when the application uses another
property representation.
`ItemStackPacketEncoder` and `ItemStackPacketDecoder`
map registry IDs and component patch states; component content projection is explicit in each direction. Packet values
are temporary representations, with no second mutable property store installed in the domain.

For a plain Entity round trip, obtain `entity` from `EntityChunk.rootEntities` after NBT decoding. Construct
`EntityPairingData(entityId, passengerEntityIds, vehiclePassengerRelation, leashHolderEntityId)` from the application's
tracking table; absent external relations use null. `packetCodecContext` comes from negotiation as above. Construct
`EntityPacketWriteMappings(...)` and `EntityPacketReadMappings(...)` with the four callbacks described above, or use
the `fromProperties(...)` factories. Construct `EntityPacketRequiredDataProvider(headYaw, passengers)` with callbacks
supplying any
unavailable simulation facts; use `RequirePresent` only after the Entity already contains them. Persisted NBT alone
does not provide head yaw. `EntityPacketMissingDataProvider { _, _ -> EntityPacketMissingData(DataProperties(), null) }`
explicitly chooses empty properties and unknown passengers for a receiving application:

```kotlin
fun projectEntity(
    entity: Entity,
    entityPairingData: EntityPairingData,
    packetCodecContext: PacketCodecContext,
    entityPacketWriteMappings: EntityPacketWriteMappings,
    entityPacketReadMappings: EntityPacketReadMappings,
    entityPacketRequiredDataProvider: EntityPacketRequiredDataProvider,
    entityPacketMissingDataProvider: EntityPacketMissingDataProvider,
): EntityPacketDecodeResult {
    val entityPacketEncoder = EntityPacketEncoder(EntityPacketEncoderContext(
        packetCodecContext, entityPacketWriteMappings, entityPacketRequiredDataProvider,
    ))
    val entityPacketDecoder = EntityPacketDecoder(EntityPacketDecoderContext(
        packetCodecContext, entityPacketReadMappings, entityPacketMissingDataProvider,
    ))
    val packets = entityPacketEncoder.encode(entity, entityPairingData)
    return entityPacketDecoder.decode(packets)
}
```

The convenience path replaces the last two lines with the following expression, using those same codecs and pairing
facts. The returned result still exposes the connection ID and pending packets for the application to handle:

```kotlin
return entity.toPairingPackets(entityPacketEncoder, entityPairingData).toEntity(entityPacketDecoder)
```

An ItemStack needs only registry and component mappings. Construct
`ItemStackPacketEncoder(ItemStackPacketEncoderContext(packetCodecContext, itemStackPacketWriteMappings))` and
`ItemStackPacketDecoder(ItemStackPacketDecoderContext(packetCodecContext, itemStackPacketReadMappings))`.
`ItemStackPacketWriteMappings(component)` projects each changed component;
`ItemStackPacketReadMappings(component, properties)`
decodes a received component and supplies non-network properties for each item ID. Pass the encoder's returned packet
ItemStack to the decoder; null semantic stacks map to the packet's Empty value. These already use direct `encode`/
`decode`
methods; the Entity mapping factories accept these prebuilt codecs for equipment without requesting their registries
again.

## Other world data

| Input or capability                                                      | Current public owner and shape                                                                                   |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| Entity batch registration, bundles and initial-world order               | Endpoint orchestration in `protocol-client`/`protocol-server`                                                    |
| EntityChunk, PoiChunk                                                    | No aggregate network representation or Chunk-wide codec                                                          |
| Data packs, registries, tags, feature flags                              | Configuration projection in `protocol-configuration`; separate vanilla providers                                 |
| LevelDat and world-generation settings                                   | Supply dimension/bootstrap facts to the caller; no whole-file network codec                                      |
| PlayerData                                                               | Saved schema only; individual menu, equipment and player packets do not reconstruct the file or connection state |
| MapData, ScoreboardData, advancements, statistics                        | Existing packet models and saved schemas; no public world-schema snapshot/delta converter                        |
| Raids, tickets, random sequences, Anvil records and persistence metadata | No generic client representation                                                                                 |

The library supplies data capabilities without game-content bindings. Chest menus, furnace state, merchant trades and
other game-specific mappings in the tests belong to the simulated application. A bidirectional API does not imply a
lossless disk/network round trip; only fields carried by each representation are converted.
