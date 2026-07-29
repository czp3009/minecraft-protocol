package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import org.eclipse.jgit.api.Git
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import kotlin.io.path.exists

abstract class PrepareAuxiliaryProtocolSourcesTask :
    MinecraftProtocolToolTask() {
    @TaskAction
    fun prepare() {
        val snapshot = repository.resolve(
            "protocol-specification/wiki-protocol-snapshot.json",
        ).readJsonObject()
        val minecraftVersion = snapshot.requiredString(
            "minecraft_version",
        )
        val results = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        repositories.forEach { auxiliary ->
            logger.lifecycle("Querying ${auxiliary.key} tags...")
            val tags = listTags(auxiliary)
            val tag = selectTag(auxiliary, minecraftVersion, tags)
            if (tag == null) {
                results[auxiliary.key] = jsonObjectOf(
                    "status" to
                            jsonString("exact-version-unavailable"),
                    "url" to jsonString(auxiliary.url),
                    "nearby_tags" to JsonArray(
                        nearbyTags(tags, minecraftVersion)
                            .map(::jsonString),
                    ),
                )
                logger.lifecycle(
                    "No exact $minecraftVersion tag is available in " +
                            "${auxiliary.key}.",
                )
            } else {
                val checkout = prepareClone(auxiliary, tag)
                results[auxiliary.key] = jsonObjectOf(
                    "status" to jsonString("exact-version"),
                    "url" to jsonString(auxiliary.url),
                    "tag" to jsonString(tag),
                    "commit" to jsonString(checkout.commit),
                    "cache_path" to jsonString(checkout.cachePath),
                )
                logger.lifecycle(
                    "Prepared ${auxiliary.key} $tag at " +
                            "${checkout.commit}.",
                )
            }
        }
        val output = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(minecraftVersion),
            "protocol_version" to
                    jsonNumber(snapshot.requiredInt("protocol_version")),
            "wiki_revision_id" to jsonNumber(
                snapshot.requiredObject("source")
                    .requiredInt("revision_id"),
            ),
            "repositories" to
                    kotlinx.serialization.json.JsonObject(results),
        )
        val path = repository.resolve(
            "protocol-specification/auxiliary-source-index.json",
        )
        val content = renderJson(output, sortKeys = true) + "\n"
        logger.lifecycle(
            "${if (path.writeIfChanged(content)) "updated" else "unchanged"}" +
                    ": $path",
        )
    }

    private fun listTags(auxiliary: AuxiliaryRepository): List<String> {
        val references = retryGit("query ${auxiliary.key} tags") {
            Git.lsRemoteRepository()
                .setRemote(auxiliary.url)
                .setHeads(false)
                .setTags(true)
                .call()
        }
        val tags = references.mapNotNull { reference ->
            reference.name
                .takeIf { it.startsWith("refs/tags/") }
                ?.removePrefix("refs/tags/")
                ?.takeIf { !it.endsWith("^{}") }
        }
        check(tags.isNotEmpty()) {
            "${auxiliary.key} returned no Git tags"
        }
        return tags
    }

    private fun selectTag(
        auxiliary: AuxiliaryRepository,
        version: String,
        tags: List<String>,
    ): String? {
        val escaped = Regex.escape(version)
        if (auxiliary.key == "mcprotocollib") {
            return tags.filter {
                it.matches(Regex("$escaped-\\d+"))
            }.maxByOrNull {
                it.substringAfterLast('-').toInt()
            }
        }
        val dated = tags.filter {
            it.matches(
                Regex(
                    """\d{4}\.\d{2}\.\d{2}[a-z]?-$escaped""",
                ),
            )
        }
        if (dated.isNotEmpty()) return dated.maxOrNull()
        return tags.filter {
            it.matches(Regex("$escaped(?:-\\d+)?"))
        }.maxOrNull()
    }

    private fun nearbyTags(
        tags: List<String>,
        version: String,
    ): List<String> {
        val versionPrefix = version.substringBefore('.')
        return tags.filter {
            version in it ||
                    Regex(
                        """(?:^|-)${Regex.escape(versionPrefix)}\.""",
                    ).containsMatchIn(it)
        }.sorted().takeLast(5)
    }

    private fun prepareClone(
        auxiliary: AuxiliaryRepository,
        tag: String,
    ): Checkout {
        require(tag.matches(Regex("[0-9A-Za-z._-]+"))) {
            "Unsafe Git tag selected for ${auxiliary.key}: $tag"
        }
        val cacheRoot = repository.resolve(
            "build/protocol-reference/auxiliary/${auxiliary.key}",
        ).toAbsolutePath().normalize()
        val destination = cacheRoot.resolve(tag).normalize()
        check(destination.parent == cacheRoot) {
            "Resolved auxiliary cache escaped its parent"
        }
        val git = if (destination.exists()) {
            check(destination.resolve(".git").exists()) {
                "Auxiliary cache exists but is not a Git checkout: " +
                        destination
            }
            Git.open(destination.toFile())
        } else {
            Files.createDirectories(destination.parent)
            retryGit("clone ${auxiliary.key} $tag") {
                Git.cloneRepository()
                    .setURI(auxiliary.url)
                    .setDirectory(destination.toFile())
                    .setBranch("refs/tags/$tag")
                    .setDepth(1)
                    .setCloneAllBranches(false)
                    .call()
            }
        }
        git.use {
            val origin = it.repository.config.getString(
                "remote",
                "origin",
                "url",
            ) ?: error(
                "Auxiliary cache has no origin: $destination",
            )
            check(
                origin.trimEnd('/') == auxiliary.url.trimEnd('/'),
            ) {
                "Auxiliary cache has unexpected origin $origin: " +
                        destination
            }
            val head = it.repository.resolve("HEAD")
                ?: error("Auxiliary cache has no HEAD: $destination")
            val taggedCommit = it.repository.resolve(
                "refs/tags/$tag^{commit}",
            ) ?: error(
                "${auxiliary.key} checkout does not contain tag $tag",
            )
            check(head == taggedCommit) {
                "${auxiliary.key} checkout HEAD ${head.name} does not " +
                        "match $tag (${taggedCommit.name})"
            }
            return Checkout(
                commit = head.name,
                cachePath = repository.relativize(destination)
                    .toString()
                    .replace('\\', '/'),
            )
        }
    }

    private fun <T> retryGit(
        description: String,
        attempts: Int = 3,
        action: () -> T,
    ): T {
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return action()
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt + 1 < attempts) {
                    Thread.sleep(500L shl attempt)
                }
            }
        }
        error(
            "Git operation '$description' failed after $attempts " +
                    "attempts: ${lastFailure?.message}",
        )
    }

    private data class Checkout(
        val commit: String,
        val cachePath: String,
    )

    private data class AuxiliaryRepository(
        val key: String,
        val url: String,
    )

    private companion object {
        val repositories = listOf(
            AuxiliaryRepository(
                "mcprotocollib",
                "https://github.com/GeyserMC/MCProtocolLib.git",
            ),
            AuxiliaryRepository(
                "minestom",
                "https://github.com/Minestom/Minestom.git",
            ),
        )
    }
}
