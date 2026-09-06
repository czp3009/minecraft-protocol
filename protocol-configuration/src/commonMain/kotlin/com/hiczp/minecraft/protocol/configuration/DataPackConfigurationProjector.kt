package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlin.coroutines.cancellation.CancellationException

enum class DataPackRegistryMergeMode {
    /** Retain base entries, replace matching values, and append new IDs deterministically. */
    OVERLAY,

    /** Build the synchronized registry only from resources visible to this projector. */
    REPLACE,
}

/** Converts one resolved data-pack resource into its valid optional network registry NBT payload. */
fun interface DataPackRegistryEntryProjector {
    fun project(
        registryEntryId: Identifier,
        resolvedDataPackResource: ResolvedDataPackResource,
        resolvedDataPackStack: ResolvedDataPackStack,
    ): NbtTag?
}

data class DataPackRegistryProjector(
    val registryId: Identifier,
    val dataPackResourceType: DataPackResourceType = DataPackResourceType(registryId.path),
    val dataPackRegistryMergeMode: DataPackRegistryMergeMode = DataPackRegistryMergeMode.OVERLAY,
    val dataPackRegistryEntryProjector: DataPackRegistryEntryProjector,
)

/** Structured refusal to guess a registry's disk-codec to network-codec transformation. */
class MissingDataPackRegistryProjectorsException(
    val unprojectedResourceIdsByRegistryId: Map<Identifier, List<DataPackResourceId>>,
) : IllegalArgumentException(
    unprojectedResourceIdsByRegistryId.entries.joinToString(
        prefix = "Missing data-pack registry projectors: ",
        separator = "; ",
    ) { (registryId, dataPackResourceIds) -> "$registryId (${dataPackResourceIds.joinToString()})" },
)

/** A caller-supplied registry entry projector failed for one identified resource. */
class DataPackRegistryProjectionException(
    val registryId: Identifier,
    val registryEntryId: Identifier,
    val sourceDataPackId: DataPackId,
    val sourceDataPackFilePath: DataPackFilePath,
    cause: Throwable,
) : IllegalArgumentException(
    "Could not project $registryEntryId from $sourceDataPackId:$sourceDataPackFilePath into $registryId",
    cause,
)

class DataPackTagProjectionException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Explicit policy for the lossy [ResolvedDataPackStack] to [ConfigurationData] boundary.
 *
 * [preprojectedDataPackIds] identifies resources already represented exactly by [baseConfigurationData]. The vanilla preset
 * uses this for the generated core pack. A custom or modded registry resource from another pack requires a matching
 * [dataPackRegistryProjectors] entry; the library never assumes its JSON codec equals its network codec.
 */
class DataPackConfigurationProjector(
    val baseConfigurationData: ConfigurationData,
    val dataPackRegistryProjectors: List<DataPackRegistryProjector> = emptyList(),
    val preprojectedDataPackIds: Set<DataPackId> = emptySet(),
    val offeredKnownPacks: List<KnownPack> = baseConfigurationData.offeredKnownPacks,
    val enabledFeatureFlags: Set<Identifier> = baseConfigurationData.enabledFeatureFlags,
    val staticRegistrySchema: StaticRegistrySchema = baseConfigurationData.staticRegistrySchema,
) {
    init {
        require(
            this.dataPackRegistryProjectors.map(DataPackRegistryProjector::registryId).distinct().size ==
                    this.dataPackRegistryProjectors.size,
        ) {
            "Data-pack protocol projector has duplicate synchronized registry projectors"
        }
    }

    fun project(
        dataPackStack: DataPackStack,
        dataPackFormatVersion: DataPackFormatVersion? = null,
    ): ResolvedConfigurationData {
        val dataPackFeatureFlags = dataPackStack.dataPacks
            .flatMap { dataPack -> dataPack.dataPackMetadata?.enabledFeatureFlags.orEmpty() }
            .map { featureId -> Identifier(featureId) }
        return project(
            dataPackStack.resolve(dataPackFormatVersion),
            enabledFeatureFlags + dataPackFeatureFlags,
        )
    }

    fun project(
        resolvedDataPackStack: ResolvedDataPackStack,
        enabledFeatureFlags: Set<Identifier> = this.enabledFeatureFlags,
    ): ResolvedConfigurationData {
        val baseSynchronizedRegistries = baseConfigurationData.synchronizedRegistryPackets(emptyList())
        val dataPackRegistryProjectorsById = dataPackRegistryProjectors.associateBy(
            DataPackRegistryProjector::registryId,
        )
        val dataPackResourceTypesByRegistryId = linkedMapOf<Identifier, DataPackResourceType>()
        baseSynchronizedRegistries.forEach { clientboundRegistryDataPacket ->
            dataPackResourceTypesByRegistryId[clientboundRegistryDataPacket.registry] =
                DataPackResourceType(clientboundRegistryDataPacket.registry.path)
        }
        dataPackRegistryProjectors.forEach { dataPackRegistryProjector ->
            dataPackResourceTypesByRegistryId[dataPackRegistryProjector.registryId] =
                dataPackRegistryProjector.dataPackResourceType
        }

        val unprojectedResourceIdsByRegistryId = linkedMapOf<Identifier, List<DataPackResourceId>>()
        dataPackResourceTypesByRegistryId.forEach { (registryId, dataPackResourceType) ->
            if (registryId in dataPackRegistryProjectorsById) return@forEach
            val unprojectedDataPackResources = unprojectedDataPackResources(
                resolvedDataPackStack,
                dataPackResourceType,
            )
            if (unprojectedDataPackResources.isNotEmpty()) {
                unprojectedResourceIdsByRegistryId[registryId] =
                    unprojectedDataPackResources.keys.sortedBy(DataPackResourceId::toString)
            }
        }
        if (unprojectedResourceIdsByRegistryId.isNotEmpty()) {
            throw MissingDataPackRegistryProjectorsException(unprojectedResourceIdsByRegistryId)
        }

        var synchronizedRegistriesChanged = false
        val synchronizedRegistriesById = linkedMapOf<Identifier, ClientboundRegistryDataPacket>()
        baseSynchronizedRegistries.forEach { clientboundRegistryDataPacket ->
            val dataPackResourceType =
                dataPackResourceTypesByRegistryId.getValue(clientboundRegistryDataPacket.registry)
            val retainedRegistryEntries = clientboundRegistryDataPacket.entries.filterNot { registryEntry ->
                resolvedDataPackStack.filtersBaseResource(
                    dataPackResourceType.path(registryEntry.id.toDataPackResourceId()),
                )
            }
            if (retainedRegistryEntries.size != clientboundRegistryDataPacket.entries.size) {
                synchronizedRegistriesChanged = true
            }
            synchronizedRegistriesById[clientboundRegistryDataPacket.registry] =
                if (retainedRegistryEntries.size == clientboundRegistryDataPacket.entries.size) {
                    clientboundRegistryDataPacket
                } else {
                    ClientboundRegistryDataPacket(clientboundRegistryDataPacket.registry, retainedRegistryEntries)
                }
        }
        dataPackRegistryProjectors.forEach { dataPackRegistryProjector ->
            val unprojectedDataPackResources = unprojectedDataPackResources(
                resolvedDataPackStack,
                dataPackRegistryProjector.dataPackResourceType,
            )
            if (
                unprojectedDataPackResources.isEmpty() &&
                dataPackRegistryProjector.dataPackRegistryMergeMode == DataPackRegistryMergeMode.OVERLAY
            ) {
                return@forEach
            }
            val baseClientboundRegistryDataPacket = synchronizedRegistriesById[dataPackRegistryProjector.registryId]
            synchronizedRegistriesById[dataPackRegistryProjector.registryId] = projectSynchronizedRegistry(
                baseClientboundRegistryDataPacket,
                dataPackRegistryProjector,
                unprojectedDataPackResources,
                resolvedDataPackStack,
            )
            synchronizedRegistriesChanged = true
        }

        val completeSynchronizedRegistryPackets = synchronizedRegistriesById.values.toList()
        val registryTags = projectRegistryTags(resolvedDataPackStack, completeSynchronizedRegistryPackets)
        val knownPackSynchronizedRegistryPackets =
            if (!synchronizedRegistriesChanged && offeredKnownPacks == baseConfigurationData.offeredKnownPacks) {
                baseConfigurationData.synchronizedRegistryPackets(offeredKnownPacks)
            } else {
                completeSynchronizedRegistryPackets
            }
        return ResolvedConfigurationData(
            offeredKnownPacks = offeredKnownPacks,
            enabledFeatureFlags = enabledFeatureFlags,
            completeSynchronizedRegistryPackets = completeSynchronizedRegistryPackets,
            knownPackSynchronizedRegistryPackets = knownPackSynchronizedRegistryPackets,
            registryTags = registryTags,
            staticRegistrySchema = staticRegistrySchema,
        )
    }

    private fun unprojectedDataPackResources(
        resolvedDataPackStack: ResolvedDataPackStack,
        dataPackResourceType: DataPackResourceType,
    ): Map<DataPackResourceId, ResolvedDataPackResource> =
        resolvedDataPackStack.resources(dataPackResourceType).filterValues { resolvedDataPackResource ->
            resolvedDataPackResource.sourceDataPackId !in preprojectedDataPackIds
        }

    private fun projectSynchronizedRegistry(
        baseClientboundRegistryDataPacket: ClientboundRegistryDataPacket?,
        dataPackRegistryProjector: DataPackRegistryProjector,
        resolvedDataPackResources: Map<DataPackResourceId, ResolvedDataPackResource>,
        resolvedDataPackStack: ResolvedDataPackStack,
    ): ClientboundRegistryDataPacket {
        val projectedRegistryEntries = resolvedDataPackResources.entries
            .associate { (dataPackResourceId, resolvedDataPackResource) ->
                val registryEntryId = dataPackResourceId.toIdentifier()
                val registryEntryData = try {
                    dataPackRegistryProjector.dataPackRegistryEntryProjector.project(
                        registryEntryId,
                        resolvedDataPackResource,
                        resolvedDataPackStack,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    throw DataPackRegistryProjectionException(
                        registryId = dataPackRegistryProjector.registryId,
                        registryEntryId = registryEntryId,
                        sourceDataPackId = resolvedDataPackResource.sourceDataPackId,
                        sourceDataPackFilePath = resolvedDataPackResource.sourceDataPackFilePath,
                        cause = failure,
                    )
                }
                registryEntryId to RegistryEntry(registryEntryId, registryEntryData)
            }
        val registryEntries = when (dataPackRegistryProjector.dataPackRegistryMergeMode) {
            DataPackRegistryMergeMode.REPLACE -> projectedRegistryEntries.values.sortedBy { it.id.value }
            DataPackRegistryMergeMode.OVERLAY -> buildList {
                val retainedRegistryEntryIds = mutableSetOf<Identifier>()
                baseClientboundRegistryDataPacket?.entries.orEmpty().forEach { registryEntry ->
                    add(projectedRegistryEntries[registryEntry.id] ?: registryEntry)
                    retainedRegistryEntryIds += registryEntry.id
                }
                projectedRegistryEntries.entries
                    .filter { (registryEntryId) -> registryEntryId !in retainedRegistryEntryIds }
                    .sortedBy { (registryEntryId) -> registryEntryId.value }
                    .forEach { (_, registryEntry) -> add(registryEntry) }
            }
        }
        return ClientboundRegistryDataPacket(dataPackRegistryProjector.registryId, registryEntries)
    }

    private fun projectRegistryTags(
        resolvedDataPackStack: ResolvedDataPackStack,
        synchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    ): List<RegistryTags> {
        val baseRegistryTagsByRegistryId = baseConfigurationData.registryTags.associateBy(RegistryTags::registry)
        val registryEntryIdsByRegistryId = synchronizedRegistryPackets.associate { clientboundRegistryDataPacket ->
            clientboundRegistryDataPacket.registry to clientboundRegistryDataPacket.entries.map(RegistryEntry::id)
        }.toMutableMap()
        staticRegistrySchema.registries.forEach { (registryId, registryEntryIds) ->
            if (registryId !in registryEntryIdsByRegistryId) {
                registryEntryIdsByRegistryId[registryId] = registryEntryIds
            }
        }
        val registryOrder = buildList {
            addAll(baseConfigurationData.registryTags.map(RegistryTags::registry))
            registryEntryIdsByRegistryId.keys.filter { registryId -> registryId !in this }.forEach(::add)
        }
        return registryOrder.mapNotNull { registryId ->
            val registryEntryIds = registryEntryIdsByRegistryId[registryId] ?: return@mapNotNull null
            val dataPackResourceType = DataPackResourceType("tags/${registryId.path}")
            val resolvedDataPackResources = unprojectedDataPackResources(
                resolvedDataPackStack,
                dataPackResourceType,
            )
            val baseRegistryTags = baseRegistryTagsByRegistryId[registryId]
            val retainedBaseTagDefinitions = baseRegistryTags?.tags.orEmpty().filterNot { tagDefinition ->
                resolvedDataPackStack.filtersBaseResource(
                    dataPackResourceType.path(tagDefinition.name.toDataPackResourceId()),
                )
            }
            val remappedBaseTagDefinitions = remapBaseTagDefinitions(
                registryId,
                registryEntryIds,
                retainedBaseTagDefinitions,
            )
            if (resolvedDataPackResources.isEmpty()) {
                if (baseRegistryTags == null) null else RegistryTags(registryId, remappedBaseTagDefinitions)
            } else {
                projectRegistryTags(
                    registryId = registryId,
                    registryEntryIds = registryEntryIds,
                    baseTagDefinitions = remappedBaseTagDefinitions,
                    resolvedDataPackResources = resolvedDataPackResources,
                )
            }
        }
    }

    private fun remapBaseTagDefinitions(
        registryId: Identifier,
        registryEntryIds: List<Identifier>,
        baseTagDefinitions: List<TagDefinition>,
    ): List<TagDefinition> {
        if (baseTagDefinitions.isEmpty()) return emptyList()
        val baseRegistryIdMap = baseConfigurationData.completePacketCodecContext.registry(registryId)
            ?: throw DataPackTagProjectionException("Base tags refer to missing registry $registryId")
        val targetRawIdsByRegistryEntryId = registryEntryIds.withIndex()
            .associate { (rawId, registryEntryId) -> registryEntryId to rawId }
        return baseTagDefinitions.map { tagDefinition ->
            TagDefinition(
                name = tagDefinition.name,
                entries = tagDefinition.entries.mapNotNull { baseRawId ->
                    val registryEntryId = baseRegistryIdMap[baseRawId]?.id
                        ?: throw DataPackTagProjectionException(
                            "Base tag ${tagDefinition.name} in $registryId refers to unknown raw ID $baseRawId",
                        )
                    targetRawIdsByRegistryEntryId[registryEntryId]
                },
            )
        }
    }

    private fun projectRegistryTags(
        registryId: Identifier,
        registryEntryIds: List<Identifier>,
        baseTagDefinitions: List<TagDefinition>,
        resolvedDataPackResources: Map<DataPackResourceId, ResolvedDataPackResource>,
    ): RegistryTags {
        val rawIdsByRegistryEntryId = registryEntryIds.withIndex().associate { (rawId, registryEntryId) ->
            registryEntryId to rawId
        }
        val baseTagDefinitionsById = baseTagDefinitions.associateBy(TagDefinition::name)
        val dataPackTagFilesById = resolvedDataPackResources.entries
            .associate { (dataPackResourceId, resolvedDataPackResource) ->
                val tagId = dataPackResourceId.toIdentifier()
                val dataPackTagFile = try {
                    resolvedDataPackResource.decodeDataPackTagFile()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    throw DataPackTagProjectionException(
                        "Could not project tag $tagId in registry $registryId",
                        failure,
                    )
                }
                tagId to dataPackTagFile
            }
        val resolvedTagRawIdsById = linkedMapOf<Identifier, List<Int>>()
        val visitingTagIds = linkedSetOf<Identifier>()

        fun resolveTagRawIds(tagId: Identifier): List<Int>? {
            resolvedTagRawIdsById[tagId]?.let { return it }
            val dataPackTagFile = dataPackTagFilesById[tagId] ?: return baseTagDefinitionsById[tagId]?.entries
            if (!visitingTagIds.add(tagId)) {
                throw DataPackTagProjectionException(
                    "Cyclic data-pack tag reference in $registryId: ${visitingTagIds.joinToString()} -> $tagId",
                )
            }
            val resolvedTagRawIds = buildList {
                if (!dataPackTagFile.replacesExistingValues) {
                    addAll(baseTagDefinitionsById[tagId]?.entries.orEmpty())
                }
                dataPackTagFile.dataPackTagValues.forEach { dataPackTagValue ->
                    val referencedId = dataPackTagValue.dataPackResourceId.toIdentifier()
                    val referencedRawIds = if (dataPackTagValue.isTagReference) {
                        resolveTagRawIds(referencedId)
                    } else {
                        rawIdsByRegistryEntryId[referencedId]?.let(::listOf)
                    }
                    if (referencedRawIds == null && dataPackTagValue.isRequired) {
                        val referenceKind = if (dataPackTagValue.isTagReference) "tag" else "entry"
                        throw DataPackTagProjectionException(
                            "Tag $tagId in $registryId requires missing $referenceKind $referencedId",
                        )
                    }
                    if (referencedRawIds != null) addAll(referencedRawIds)
                }
            }.distinct()
            visitingTagIds.remove(tagId)
            resolvedTagRawIdsById[tagId] = resolvedTagRawIds
            return resolvedTagRawIds
        }

        dataPackTagFilesById.keys.forEach(::resolveTagRawIds)
        val projectedTagDefinitions = buildList {
            baseTagDefinitions.forEach { baseTagDefinition ->
                add(
                    TagDefinition(
                        baseTagDefinition.name,
                        resolvedTagRawIdsById[baseTagDefinition.name] ?: baseTagDefinition.entries,
                    ),
                )
            }
            dataPackTagFilesById.keys
                .filter { tagId -> tagId !in baseTagDefinitionsById }
                .sortedBy(Identifier::value)
                .forEach { tagId -> add(TagDefinition(tagId, resolvedTagRawIdsById.getValue(tagId))) }
        }
        return RegistryTags(registryId, projectedTagDefinitions)
    }
}

private fun Identifier.toDataPackResourceId(): DataPackResourceId = DataPackResourceId(namespace, path)

private fun DataPackResourceId.toIdentifier(): Identifier = Identifier(namespace, path)

fun DataPackStack.toConfigurationData(
    dataPackConfigurationProjector: DataPackConfigurationProjector,
    dataPackFormatVersion: DataPackFormatVersion? = null,
): ResolvedConfigurationData = dataPackConfigurationProjector.project(this, dataPackFormatVersion)

fun ResolvedDataPackStack.toConfigurationData(
    dataPackConfigurationProjector: DataPackConfigurationProjector,
): ResolvedConfigurationData = dataPackConfigurationProjector.project(this)
