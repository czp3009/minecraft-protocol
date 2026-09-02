package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.world.format.NAMESPACE_PATTERN
import com.hiczp.minecraft.world.format.RESOURCE_PATH_PATTERN
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
    val dataPackIds: List<DataPackId>,
    resolvedDataPackResources: Collection<ResolvedDataPackResource>,
    val appliedDataPackFilters: List<AppliedDataPackFilter> = emptyList(),
) {
    val resolvedDataPackResources: Map<DataPackResourcePath, ResolvedDataPackResource> =
        resolvedDataPackResources.associateBy(ResolvedDataPackResource::dataPackResourcePath)

    init {
        require(this.resolvedDataPackResources.size == resolvedDataPackResources.size) {
            "A resolved data-pack stack must have at most one effective resource per path"
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

    override fun equals(other: Any?): Boolean =
        other is ResolvedDataPackStack &&
                dataPackIds == other.dataPackIds &&
                resolvedDataPackResources == other.resolvedDataPackResources &&
                appliedDataPackFilters == other.appliedDataPackFilters

    override fun hashCode(): Int {
        var result = dataPackIds.hashCode()
        result = 31 * result + resolvedDataPackResources.hashCode()
        return 31 * result + appliedDataPackFilters.hashCode()
    }

    override fun toString(): String =
        "ResolvedDataPackStack(dataPackIds=$dataPackIds, resolvedDataPackResources=$resolvedDataPackResources, appliedDataPackFilters=$appliedDataPackFilters)"
}

/** Low-to-high-priority pack stack. Callers can construct or replace any stage in memory. */
data class DataPackStack(val dataPacks: List<DataPack>) {
    constructor(vararg dataPacks: DataPack) : this(dataPacks.asList())

    fun resolve(dataPackFormatVersion: DataPackFormatVersion? = null): ResolvedDataPackStack {
        val resolvedDataPackResources = linkedMapOf<DataPackResourcePath, ResolvedDataPackResource>()
        val appliedDataPackFilters = mutableListOf<AppliedDataPackFilter>()
        dataPacks.forEach { dataPack ->
            dataPack.dataPackMetadata?.dataPackFilterPatterns.orEmpty().forEach { dataPackFilterPattern ->
                appliedDataPackFilters += AppliedDataPackFilter(dataPack.dataPackId, dataPackFilterPattern)
                resolvedDataPackResources.keys.filter(dataPackFilterPattern::matches)
                    .forEach(resolvedDataPackResources::remove)
            }
            dataPack.effectiveDataPackFiles(dataPackFormatVersion)
                .forEach { (dataPackResourcePath, effectiveDataPackFile) ->
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
                            effectiveDataPackFile,
                        )
                    } else {
                        ResolvedDataPackResource(
                            dataPackResourcePath = dataPackResourcePath,
                            dataPackFileContent = effectiveDataPackFile.dataPackFileContent,
                            sourceDataPackId = dataPack.dataPackId,
                            sourceDataPackFilePath = effectiveDataPackFile.dataPackFilePath,
                        )
                    }
                }
        }
        return ResolvedDataPackStack(
            dataPacks.map(DataPack::dataPackId),
            resolvedDataPackResources.values,
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
    effectiveDataPackFile: EffectiveDataPackFile,
): ResolvedDataPackResource {
    val currentDataPackFileContent = effectiveDataPackFile.dataPackFileContent
    val previousDataPackFileContent = previousResolvedDataPackResource.dataPackFileContent
    if (
        currentDataPackFileContent !is DataPackFileContent.JsonFile ||
        previousDataPackFileContent !is DataPackFileContent.JsonFile
    ) {
        return ResolvedDataPackResource(
            dataPackResourcePath,
            currentDataPackFileContent,
            sourceDataPackId,
            effectiveDataPackFile.dataPackFilePath,
        )
    }
    val currentJsonObject = currentDataPackFileContent.jsonElement.jsonObject
    if (currentJsonObject["replace"]?.jsonPrimitive?.boolean == true) {
        return ResolvedDataPackResource(
            dataPackResourcePath,
            currentDataPackFileContent,
            sourceDataPackId,
            effectiveDataPackFile.dataPackFilePath,
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
        sourceDataPackFilePath = effectiveDataPackFile.dataPackFilePath,
        contributingDataPackIds = previousResolvedDataPackResource.contributingDataPackIds + sourceDataPackId,
    )
}
