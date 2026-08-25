package com.hiczp.minecraft.world.format.datapack

import kotlinx.serialization.json.*

/** One effective resource with enough provenance to audit replacement and tag merging. */
data class ResolvedDataPackResource(
    val dataPackResourcePath: DataPackResourcePath,
    val dataPackFileContent: DataPackFileContent,
    val sourceDataPackId: DataPackId,
    val sourceDataPackFilePath: DataPackFilePath,
    val contributingDataPackIds: List<DataPackId> = listOf(sourceDataPackId),
)

data class AppliedDataPackFilter(
    val sourceDataPackId: DataPackId,
    val dataPackFilterPattern: DataPackFilterPattern,
)

/** A manually constructible, merged data-pack view used by downstream runtime projections. */
class ResolvedDataPackStack(
    dataPackIds: List<DataPackId>,
    resolvedDataPackResources: Map<DataPackResourcePath, ResolvedDataPackResource>,
    appliedDataPackFilters: List<AppliedDataPackFilter> = emptyList(),
) {
    val dataPackIds: List<DataPackId> = dataPackIds.toList()
    val resolvedDataPackResources: Map<DataPackResourcePath, ResolvedDataPackResource> =
        resolvedDataPackResources.toMap()
    val appliedDataPackFilters: List<AppliedDataPackFilter> = appliedDataPackFilters.toList()

    init {
        require(this.dataPackIds.distinct().size == this.dataPackIds.size) {
            "A resolved data-pack stack has duplicate pack IDs"
        }
        require(this.resolvedDataPackResources.all { (dataPackResourcePath, resolvedDataPackResource) ->
            dataPackResourcePath == resolvedDataPackResource.dataPackResourcePath
        }) {
            "Resolved data-pack resource keys must match their paths"
        }
    }

    fun resource(dataPackResourcePath: DataPackResourcePath): ResolvedDataPackResource? =
        resolvedDataPackResources[dataPackResourcePath]

    /**
     * True when a pack filter removes external lower/base content at [dataPackResourcePath], even if the stack supplies
     * a replacement.
     */
    fun filtersBaseResource(dataPackResourcePath: DataPackResourcePath): Boolean =
        appliedDataPackFilters.any { it.dataPackFilterPattern.matches(dataPackResourcePath) }

    fun resource(
        dataPackResourceType: DataPackResourceType,
        dataPackResourceId: DataPackResourceId,
    ): ResolvedDataPackResource? = resolvedDataPackResources[dataPackResourceType.path(dataPackResourceId)]

    fun resources(
        dataPackResourceType: DataPackResourceType,
    ): Map<DataPackResourceId, ResolvedDataPackResource> = buildMap {
        resolvedDataPackResources.forEach { (dataPackResourcePath, resolvedDataPackResource) ->
            dataPackResourceType.id(dataPackResourcePath)?.let { dataPackResourceId ->
                put(dataPackResourceId, resolvedDataPackResource)
            }
        }
    }
}

/** Low-to-high-priority pack stack. Callers can construct or replace any stage in memory. */
class DataPackStack(dataPacks: List<DataPack>) {
    val dataPacks: List<DataPack> = dataPacks.toList()

    init {
        require(this.dataPacks.map(DataPack::dataPackId).distinct().size == this.dataPacks.size) {
            "A data-pack stack has duplicate pack IDs"
        }
    }

    constructor(vararg dataPacks: DataPack) : this(dataPacks.toList())

    fun resolve(dataPackFormatVersion: DataPackFormatVersion? = null): ResolvedDataPackStack {
        val resolvedDataPackResources = linkedMapOf<DataPackResourcePath, ResolvedDataPackResource>()
        val appliedDataPackFilters = mutableListOf<AppliedDataPackFilter>()
        dataPacks.forEach { dataPack ->
            dataPack.dataPackMetadata?.dataPackFilterPatterns.orEmpty().forEach { dataPackFilterPattern ->
                appliedDataPackFilters += AppliedDataPackFilter(dataPack.dataPackId, dataPackFilterPattern)
                resolvedDataPackResources.keys.filter(dataPackFilterPattern::matches)
                    .forEach(resolvedDataPackResources::remove)
            }
            dataPack.effectiveDataPackFiles(dataPackFormatVersion).forEach { (dataPackResourcePath, dataPackFile) ->
                val previousResolvedDataPackResource = resolvedDataPackResources[dataPackResourcePath]
                resolvedDataPackResources[dataPackResourcePath] = if (
                    previousResolvedDataPackResource != null &&
                    dataPackResourcePath.path.startsWith("tags/") &&
                    dataPackResourcePath.path.endsWith(".json")
                ) {
                    mergeTagFile(
                        previousResolvedDataPackResource,
                        dataPack.dataPackId,
                        dataPackResourcePath,
                        dataPackFile,
                    )
                } else {
                    ResolvedDataPackResource(
                        dataPackResourcePath = dataPackResourcePath,
                        dataPackFileContent = dataPackFile.dataPackFileContent,
                        sourceDataPackId = dataPack.dataPackId,
                        sourceDataPackFilePath = dataPackFile.dataPackFilePath,
                    )
                }
            }
        }
        return ResolvedDataPackStack(
            dataPacks.map(DataPack::dataPackId),
            resolvedDataPackResources,
            appliedDataPackFilters,
        )
    }
}

internal fun DataPack.effectiveDataPackFiles(
    dataPackFormatVersion: DataPackFormatVersion?,
): Map<DataPackResourcePath, EffectiveDataPackFile> {
    val effectiveDataPackFiles = linkedMapOf<DataPackResourcePath, EffectiveDataPackFile>()
    dataPackFileContentsByPath.entries.sortedBy { it.key.value }.forEach { (dataPackFilePath, dataPackFileContent) ->
        dataPackFilePath.resourceBelow(prefix = null)?.let { dataPackResourcePath ->
            effectiveDataPackFiles[dataPackResourcePath] = EffectiveDataPackFile(dataPackFilePath, dataPackFileContent)
        }
    }
    if (dataPackFormatVersion == null) return effectiveDataPackFiles
    dataPackMetadata?.dataPackOverlays.orEmpty()
        .filter { dataPackFormatVersion in it.supportedDataPackFormatVersionRange }
        .forEach { dataPackOverlay ->
            dataPackFileContentsByPath.entries.sortedBy { it.key.value }
                .forEach { (dataPackFilePath, dataPackFileContent) ->
                    dataPackFilePath.resourceBelow(dataPackOverlay.overlayDirectory)?.let { dataPackResourcePath ->
                        effectiveDataPackFiles[dataPackResourcePath] =
                            EffectiveDataPackFile(dataPackFilePath, dataPackFileContent)
                    }
                }
        }
    return effectiveDataPackFiles
}

private fun DataPackFilePath.resourceBelow(prefix: DataPackFilePath?): DataPackResourcePath? {
    val prefixSegments = prefix?.segments.orEmpty()
    val pathSegments = segments
    if (pathSegments.size < prefixSegments.size + 3) return null
    if (pathSegments.take(prefixSegments.size) != prefixSegments) return null
    val dataIndex = prefixSegments.size
    if (pathSegments[dataIndex] != "data") return null
    val namespace = pathSegments[dataIndex + 1]
    val relativeDataPackResourcePath = pathSegments.drop(dataIndex + 2).joinToString("/")
    if (!namespace.matches(NAMESPACE_PATTERN) || !relativeDataPackResourcePath.matches(RESOURCE_PATH_PATTERN)) {
        return null
    }
    return DataPackResourcePath(namespace, relativeDataPackResourcePath)
}

private fun mergeTagFile(
    previousResolvedDataPackResource: ResolvedDataPackResource,
    sourceDataPackId: DataPackId,
    dataPackResourcePath: DataPackResourcePath,
    dataPackFile: EffectiveDataPackFile,
): ResolvedDataPackResource {
    val currentDataPackFileContent = dataPackFile.dataPackFileContent
    val previousDataPackFileContent = previousResolvedDataPackResource.dataPackFileContent
    if (
        currentDataPackFileContent !is DataPackFileContent.JsonFile ||
        previousDataPackFileContent !is DataPackFileContent.JsonFile
    ) {
        return ResolvedDataPackResource(
            dataPackResourcePath,
            currentDataPackFileContent,
            sourceDataPackId,
            dataPackFile.dataPackFilePath,
        )
    }
    val currentJsonObject = currentDataPackFileContent.jsonElement.jsonObject
    if (currentJsonObject["replace"]?.jsonPrimitive?.boolean == true) {
        return ResolvedDataPackResource(
            dataPackResourcePath,
            currentDataPackFileContent,
            sourceDataPackId,
            dataPackFile.dataPackFilePath,
        )
    }
    val previousJsonObject = previousDataPackFileContent.jsonElement.jsonObject
    val mergedTagValues = previousJsonObject.getValue("values").jsonArray +
            currentJsonObject.getValue("values").jsonArray
    val mergedJsonObject = JsonObject(
        previousJsonObject + currentJsonObject + mapOf(
            "replace" to JsonPrimitive(false),
            "values" to JsonArray(mergedTagValues),
        ),
    )
    return ResolvedDataPackResource(
        dataPackResourcePath = dataPackResourcePath,
        dataPackFileContent = DataPackFileContent.JsonFile(mergedJsonObject),
        sourceDataPackId = sourceDataPackId,
        sourceDataPackFilePath = dataPackFile.dataPackFilePath,
        contributingDataPackIds = previousResolvedDataPackResource.contributingDataPackIds + sourceDataPackId,
    )
}
