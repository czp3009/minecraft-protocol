package com.hiczp.minecraft.world.format.datapack

import kotlinx.serialization.json.*

/** One effective resource with enough provenance to audit replacement and tag merging. */
data class ResolvedDataPackResource(
    val path: DataPackResourcePath,
    val content: DataPackFileContent,
    val sourcePack: DataPackId,
    val sourcePath: DataPackPath,
    val contributors: List<DataPackId> = listOf(sourcePack),
)

data class AppliedDataPackFilter(
    val sourcePack: DataPackId,
    val pattern: DataPackFilterPattern,
)

/** A manually constructible, merged data-pack view used by downstream runtime projections. */
class ResolvedDataPackStack(
    packs: List<DataPackId>,
    resources: Map<DataPackResourcePath, ResolvedDataPackResource>,
    appliedFilters: List<AppliedDataPackFilter> = emptyList(),
) {
    val packs: List<DataPackId> = packs.toList()
    val resources: Map<DataPackResourcePath, ResolvedDataPackResource> = resources.toMap()
    val appliedFilters: List<AppliedDataPackFilter> = appliedFilters.toList()

    init {
        require(this.packs.distinct().size == this.packs.size) { "A resolved data-pack stack has duplicate pack IDs" }
        require(this.resources.all { (path, resource) -> path == resource.path }) {
            "Resolved data-pack resource keys must match their paths"
        }
    }

    fun resource(path: DataPackResourcePath): ResolvedDataPackResource? = resources[path]

    /**
     * True when a pack filter removes external lower/base content at [path], even if the stack supplies a replacement.
     */
    fun filtersBaseResource(path: DataPackResourcePath): Boolean =
        appliedFilters.any { it.pattern.matches(path) }

    fun resource(
        type: DataPackResourceType,
        id: DataPackResourceId,
    ): ResolvedDataPackResource? = resources[type.path(id)]

    fun resources(type: DataPackResourceType): Map<DataPackResourceId, ResolvedDataPackResource> = buildMap {
        resources.forEach { (path, resource) ->
            type.id(path)?.let { id -> put(id, resource) }
        }
    }
}

/** Low-to-high-priority pack stack. Callers can construct or replace any stage in memory. */
class DataPackStack(packs: List<DataPack>) {
    val packs: List<DataPack> = packs.toList()

    init {
        require(this.packs.map(DataPack::id).distinct().size == this.packs.size) {
            "A data-pack stack has duplicate pack IDs"
        }
    }

    constructor(vararg packs: DataPack) : this(packs.toList())

    fun resolve(format: DataPackFormatVersion? = null): ResolvedDataPackStack {
        val resolved = linkedMapOf<DataPackResourcePath, ResolvedDataPackResource>()
        val filters = mutableListOf<AppliedDataPackFilter>()
        packs.forEach { pack ->
            pack.metadata?.filters.orEmpty().forEach { filter ->
                filters += AppliedDataPackFilter(pack.id, filter)
                resolved.keys.filter(filter::matches).forEach(resolved::remove)
            }
            pack.effectiveDataPackFiles(format).forEach { (resourcePath, file) ->
                val previous = resolved[resourcePath]
                resolved[resourcePath] = if (previous != null && resourcePath.isTagJson()) {
                    mergeTag(previous, pack.id, resourcePath, file)
                } else {
                    ResolvedDataPackResource(
                        path = resourcePath,
                        content = file.content,
                        sourcePack = pack.id,
                        sourcePath = file.path,
                    )
                }
            }
        }
        return ResolvedDataPackStack(packs.map(DataPack::id), resolved, filters)
    }
}

internal fun DataPack.effectiveDataPackResources(
    format: DataPackFormatVersion?,
): Map<DataPackResourcePath, DataPackFileContent> =
    effectiveDataPackFiles(format).mapValues { it.value.content }

private fun DataPack.effectiveDataPackFiles(
    format: DataPackFormatVersion?,
): Map<DataPackResourcePath, DataPackFile> {
    val result = linkedMapOf<DataPackResourcePath, DataPackFile>()
    files.entries.sortedBy { it.key.value }.forEach { (path, content) ->
        path.resourceBelow(prefix = null)?.let { resource -> result[resource] = DataPackFile(path, content) }
    }
    if (format == null) return result
    metadata?.overlays.orEmpty().filter { format in it.formats }.forEach { overlay ->
        files.entries.sortedBy { it.key.value }.forEach { (path, content) ->
            path.resourceBelow(overlay.directory)?.let { resource -> result[resource] = DataPackFile(path, content) }
        }
    }
    return result
}

private fun DataPackPath.resourceBelow(prefix: DataPackPath?): DataPackResourcePath? {
    val prefixSegments = prefix?.segments.orEmpty()
    val pathSegments = segments
    if (pathSegments.size < prefixSegments.size + 3) return null
    if (pathSegments.take(prefixSegments.size) != prefixSegments) return null
    val dataIndex = prefixSegments.size
    if (pathSegments[dataIndex] != "data") return null
    val namespace = pathSegments[dataIndex + 1]
    val relative = pathSegments.drop(dataIndex + 2).joinToString("/")
    if (!namespace.matches(NAMESPACE_PATTERN) || !relative.matches(RESOURCE_PATH_PATTERN)) return null
    return DataPackResourcePath(namespace, relative)
}

private fun DataPackResourcePath.isTagJson(): Boolean = path.startsWith("tags/") && path.endsWith(".json")

private fun mergeTag(
    previous: ResolvedDataPackResource,
    packId: DataPackId,
    path: DataPackResourcePath,
    file: DataPackFile,
): ResolvedDataPackResource {
    val currentContent = file.content
    val previousContent = previous.content
    if (currentContent !is DataPackFileContent.JsonFile || previousContent !is DataPackFileContent.JsonFile) {
        return ResolvedDataPackResource(path, currentContent, packId, file.path)
    }
    val currentJson = currentContent.element.jsonObject
    if (currentJson["replace"]?.jsonPrimitive?.boolean == true) {
        return ResolvedDataPackResource(path, currentContent, packId, file.path)
    }
    val previousJson = previousContent.element.jsonObject
    val values = previousJson.getValue("values").jsonArray + currentJson.getValue("values").jsonArray
    val merged = JsonObject(
        previousJson + currentJson + mapOf(
            "replace" to JsonPrimitive(false),
            "values" to JsonArray(values),
        ),
    )
    return ResolvedDataPackResource(
        path = path,
        content = DataPackFileContent.JsonFile(merged),
        sourcePack = packId,
        sourcePath = file.path,
        contributors = previous.contributors + packId,
    )
}
