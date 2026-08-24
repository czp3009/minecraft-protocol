package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class DataPackRegistryProjectionMode {
    /** Retain base entries, replace matching values, and append new IDs deterministically. */
    OVERLAY,

    /** Build the synchronized registry only from resources visible to this projector. */
    REPLACE,
}

/** Converts the disk/programmatic form of one registry entry into its exact optional network NBT value. */
fun interface DataPackRegistryEntryProjector {
    fun project(
        id: Identifier,
        resource: ResolvedDataPackResource,
        dataPacks: ResolvedDataPackStack,
    ): NbtTag?
}

data class DataPackSynchronizedRegistryProjector(
    val registryId: Identifier,
    val resourceType: DataPackResourceType = DataPackResourceType(registryId.path),
    val mode: DataPackRegistryProjectionMode = DataPackRegistryProjectionMode.OVERLAY,
    val entryProjector: DataPackRegistryEntryProjector,
)

/** Structured refusal to guess a registry's disk-codec to network-codec transformation. */
class MissingDataPackRegistryProjectors(
    resourcesByRegistry: Map<Identifier, List<DataPackResourceId>>,
) : IllegalArgumentException(
    resourcesByRegistry.entries.joinToString(
        prefix = "Missing data-pack registry projectors: ",
        separator = "; ",
    ) { (registry, resources) -> "$registry (${resources.joinToString()})" },
) {
    val resourcesByRegistry: Map<Identifier, List<DataPackResourceId>> = resourcesByRegistry.mapValues {
        it.value.toList()
    }
}

class DataPackTagProjectionException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Explicit policy for the lossy `ResolvedDataPackStack -> ProtocolDataSet` boundary.
 *
 * [preprojectedPacks] identifies resources already represented exactly by [base]. The vanilla preset uses this for the
 * generated core pack. A custom/modded registry resource from any other pack requires a matching [registryProjectors]
 * entry; the library never assumes its JSON codec equals its network codec.
 */
class DataPackProtocolProjection(
    val base: ProtocolDataSet,
    registryProjectors: List<DataPackSynchronizedRegistryProjector> = emptyList(),
    preprojectedPacks: Set<DataPackId> = emptySet(),
    knownPacks: List<KnownPack> = base.knownPacks,
    featureFlags: Set<Identifier> = base.featureFlags.featureFlags,
    val staticRegistries: StaticRegistrySchema = base.staticRegistries,
) {
    val registryProjectors: List<DataPackSynchronizedRegistryProjector> = registryProjectors.toList()
    val preprojectedPacks: Set<DataPackId> = preprojectedPacks.toSet()
    val knownPacks: List<KnownPack> = knownPacks.toList()
    val featureFlags: Set<Identifier> = featureFlags.toSet()

    init {
        require(this.registryProjectors.map { it.registryId }.distinct().size == this.registryProjectors.size) {
            "Data-pack protocol projection has duplicate synchronized registry projectors"
        }
        require(this.knownPacks.distinct().size == this.knownPacks.size) {
            "Data-pack protocol projection has duplicate Known Packs"
        }
    }

    fun project(
        stack: DataPackStack,
        format: DataPackFormatVersion? = null,
    ): DataPackProtocolDataSet {
        val packFeatures = stack.packs.flatMap { pack -> pack.metadata?.enabledFeatures.orEmpty() }.map { feature ->
            Identifier(feature)
        }
        return project(stack.resolve(format), featureFlags + packFeatures)
    }

    fun project(
        dataPacks: ResolvedDataPackStack,
        enabledFeatureFlags: Set<Identifier> = featureFlags,
    ): DataPackProtocolDataSet {
        val baseComplete = base.registryPackets(emptyList())
        val projectorsById = registryProjectors.associateBy(DataPackSynchronizedRegistryProjector::registryId)
        val registryTypes = linkedMapOf<Identifier, DataPackResourceType>()
        baseComplete.forEach { packet ->
            registryTypes[packet.registryId] = DataPackResourceType(packet.registryId.path)
        }
        registryProjectors.forEach { projector -> registryTypes[projector.registryId] = projector.resourceType }
        val missing = linkedMapOf<Identifier, List<DataPackResourceId>>()
        registryTypes.forEach { (registryId, type) ->
            if (registryId in projectorsById) return@forEach
            val resources = unprojectedResources(dataPacks, type)
            if (resources.isNotEmpty()) missing[registryId] = resources.keys.sortedBy(DataPackResourceId::toString)
        }
        if (missing.isNotEmpty()) throw MissingDataPackRegistryProjectors(missing)

        var changed = false
        val completeById = linkedMapOf<Identifier, RegistryDataPacket>()
        baseComplete.forEach { packet ->
            val type = registryTypes.getValue(packet.registryId)
            val entries = packet.entries.filterNot { entry ->
                dataPacks.filtersBaseResource(type.path(entry.id.toDataPackResourceId()))
            }
            if (entries.size != packet.entries.size) changed = true
            completeById[packet.registryId] = if (entries.size == packet.entries.size) {
                packet
            } else {
                RegistryDataPacket(packet.registryId, entries)
            }
        }
        registryProjectors.forEach { projector ->
            val resources = unprojectedResources(dataPacks, projector.resourceType)
            if (resources.isEmpty() && projector.mode == DataPackRegistryProjectionMode.OVERLAY) return@forEach
            val basePacket = completeById[projector.registryId]
            completeById[projector.registryId] = projectRegistry(basePacket, projector, resources, dataPacks)
            changed = true
        }
        val complete = completeById.values.toList()
        val tags = projectTags(dataPacks, complete)
        val compact = if (!changed && knownPacks == base.knownPacks) base.registryPackets(knownPacks) else complete
        return DataPackProtocolDataSet(
            minecraftVersion = base.minecraftVersion,
            protocolVersion = base.protocolVersion,
            knownPacks = knownPacks,
            featureFlags = FeatureFlagsPacket(enabledFeatureFlags),
            completeRegistries = complete,
            knownPackRegistries = compact,
            tags = tags,
            staticRegistries = staticRegistries,
        )
    }

    private fun unprojectedResources(
        dataPacks: ResolvedDataPackStack,
        type: DataPackResourceType,
    ): Map<DataPackResourceId, ResolvedDataPackResource> =
        dataPacks.resources(type).filterValues { resource -> resource.sourcePack !in preprojectedPacks }

    private fun projectRegistry(
        basePacket: RegistryDataPacket?,
        projector: DataPackSynchronizedRegistryProjector,
        resources: Map<DataPackResourceId, ResolvedDataPackResource>,
        dataPacks: ResolvedDataPackStack,
    ): RegistryDataPacket {
        val projected = resources.mapKeys { (id) -> Identifier(id.toString()) }.mapValues { (id, resource) ->
            RegistryEntry(id, projector.entryProjector.project(id, resource, dataPacks))
        }
        val entries = when (projector.mode) {
            DataPackRegistryProjectionMode.REPLACE -> projected.values.sortedBy { it.id.value }
            DataPackRegistryProjectionMode.OVERLAY -> buildList {
                val retainedIds = mutableSetOf<Identifier>()
                basePacket?.entries.orEmpty().forEach { entry ->
                    add(projected[entry.id] ?: entry)
                    retainedIds += entry.id
                }
                projected.entries.filter { it.key !in retainedIds }.sortedBy { it.key.value }.forEach { (_, entry) ->
                    add(entry)
                }
            }
        }
        return RegistryDataPacket(projector.registryId, entries)
    }

    private fun projectTags(
        dataPacks: ResolvedDataPackStack,
        registries: List<RegistryDataPacket>,
    ): ConfigurationUpdateTagsPacket {
        val baseByRegistry = base.tags.registries.associateBy(RegistryTags::registry)
        val entryIds = registries.associate { packet -> packet.registryId to packet.entries.map(RegistryEntry::id) }
            .toMutableMap()
        staticRegistries.registries.forEach { (registry, entries) ->
            if (registry !in entryIds) entryIds[registry] = entries
        }
        val registryOrder = buildList {
            addAll(base.tags.registries.map(RegistryTags::registry))
            entryIds.keys.filter { it !in this }.forEach(::add)
        }
        return ConfigurationUpdateTagsPacket(
            registryOrder.mapNotNull { registry ->
                val entries = entryIds[registry] ?: return@mapNotNull null
                val type = DataPackResourceType("tags/${registry.path}")
                val resources = unprojectedResources(dataPacks, type)
                val baseTags = baseByRegistry[registry]
                val retainedBaseTags = baseTags?.tags.orEmpty().filterNot { tag ->
                    dataPacks.filtersBaseResource(type.path(tag.name.toDataPackResourceId()))
                }
                val remappedBaseTags = remapBaseTags(registry, entries, retainedBaseTags)
                if (resources.isEmpty()) {
                    return@mapNotNull if (baseTags == null) null else RegistryTags(registry, remappedBaseTags)
                }
                projectRegistryTags(
                    registry = registry,
                    entries = entries,
                    baseTags = remappedBaseTags,
                    resources = resources,
                )
            },
        )
    }

    private fun remapBaseTags(
        registry: Identifier,
        entries: List<Identifier>,
        tags: List<TagDefinition>,
    ): List<TagDefinition> {
        if (tags.isEmpty()) return emptyList()
        val baseRegistry = base.registryContext.registry(registry)
            ?: throw DataPackTagProjectionException("Base tags refer to missing registry $registry")
        val targetRawIds = entries.withIndex().associate { (rawId, id) -> id to rawId }
        return tags.map { tag ->
            TagDefinition(
                name = tag.name,
                entries = tag.entries.mapNotNull { baseRawId ->
                    val id = baseRegistry[baseRawId]?.id ?: throw DataPackTagProjectionException(
                        "Base tag ${tag.name} in $registry refers to unknown raw ID $baseRawId",
                    )
                    targetRawIds[id]
                },
            )
        }
    }

    private fun projectRegistryTags(
        registry: Identifier,
        entries: List<Identifier>,
        baseTags: List<TagDefinition>,
        resources: Map<DataPackResourceId, ResolvedDataPackResource>,
    ): RegistryTags {
        val rawIds = entries.withIndex().associate { (rawId, id) -> id to rawId }
        val baseById = baseTags.associateBy(TagDefinition::name)
        val specifications = resources.mapKeys { (id) -> Identifier(id.toString()) }.mapValues { (id, resource) ->
            parseTagSpecification(registry, id, resource)
        }
        val resolved = linkedMapOf<Identifier, List<Int>>()
        val visiting = linkedSetOf<Identifier>()

        fun resolve(tag: Identifier): List<Int>? {
            resolved[tag]?.let { return it }
            val specification = specifications[tag] ?: return baseById[tag]?.entries
            if (!visiting.add(tag)) {
                throw DataPackTagProjectionException(
                    "Cyclic data-pack tag reference in $registry: ${visiting.joinToString()} -> $tag",
                )
            }
            val values = buildList {
                if (!specification.replace) addAll(baseById[tag]?.entries.orEmpty())
                specification.values.forEach { value ->
                    val projected = if (value.tag) resolve(value.id) else rawIds[value.id]?.let(::listOf)
                    if (projected == null && value.required) {
                        throw DataPackTagProjectionException(
                            "Tag $tag in $registry requires missing ${if (value.tag) "tag" else "entry"} ${value.id}",
                        )
                    }
                    if (projected != null) addAll(projected)
                }
            }.distinct()
            visiting.remove(tag)
            resolved[tag] = values
            return values
        }

        specifications.keys.forEach(::resolve)
        val tags = buildList {
            baseTags.forEach { tag -> add(TagDefinition(tag.name, resolved[tag.name] ?: tag.entries)) }
            specifications.keys.filter { it !in baseById }.sortedBy(Identifier::value).forEach { tag ->
                add(TagDefinition(tag, resolved.getValue(tag)))
            }
        }
        return RegistryTags(registry, tags)
    }

    private fun parseTagSpecification(
        registry: Identifier,
        tag: Identifier,
        resource: ResolvedDataPackResource,
    ): DataPackTagSpecification {
        val json = (resource.content as? DataPackFileContent.JsonFile)?.element as? JsonObject
            ?: throw DataPackTagProjectionException("Tag $tag in $registry is not a JSON object")
        val values = json["values"] as? JsonArray
            ?: throw DataPackTagProjectionException("Tag $tag in $registry has no values array")
        return DataPackTagSpecification(
            replace = (json["replace"] as? JsonPrimitive)?.booleanOrNull == true,
            values = values.map { element ->
                val primitive = element as? JsonPrimitive
                val objectValue = element as? JsonObject
                val encoded = primitive?.content ?: objectValue?.get("id")?.let { (it as? JsonPrimitive)?.content }
                ?: throw DataPackTagProjectionException("Tag $tag in $registry has an invalid value")
                val isTag = encoded.startsWith('#')
                DataPackTagValue(
                    id = Identifier(if (isTag) encoded.removePrefix("#") else encoded),
                    tag = isTag,
                    required = (objectValue?.get("required") as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            },
        )
    }

}

private data class DataPackTagSpecification(
    val replace: Boolean,
    val values: List<DataPackTagValue>,
)

private data class DataPackTagValue(
    val id: Identifier,
    val tag: Boolean,
    val required: Boolean,
)

private fun Identifier.toDataPackResourceId(): DataPackResourceId = DataPackResourceId(namespace, path)

fun DataPackStack.toProtocolDataSet(
    projection: DataPackProtocolProjection,
    format: DataPackFormatVersion? = null,
): DataPackProtocolDataSet = projection.project(this, format)

fun ResolvedDataPackStack.toProtocolDataSet(
    projection: DataPackProtocolProjection,
): DataPackProtocolDataSet = projection.project(this)
