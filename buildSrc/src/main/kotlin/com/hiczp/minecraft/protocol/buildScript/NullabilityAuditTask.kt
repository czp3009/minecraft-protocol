package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

abstract class AuditNullabilityTask : MinecraftProtocolToolTask() {
    @get:Input
    abstract val reportOnly: Property<Boolean>

    @get:Internal
    abstract val reportFile: RegularFileProperty

    @get:Internal
    abstract val ledgerFile: RegularFileProperty

    init {
        reportOnly.convention(false)
    }

    @TaskAction
    fun audit() {
        val properties = inventory()
        if (reportFile.isPresent) {
            val report = inventoryReport(properties)
            val path = reportFile.asFile.get().toPath()
            path.writeJson(report)
            logger.lifecycle("Wrote nullability inventory: $path")
        }
        if (reportOnly.get()) return
        val ledger = if (ledgerFile.isPresent) {
            ledgerFile.asFile.get().toPath()
        } else {
            repository.resolve(
                "protocol-specification/nullability-audit.yaml",
            )
        }
        val errors = validateLedger(properties, ledger)
        check(errors.isEmpty()) {
            buildString {
                appendLine(
                    "Nullability audit found ${errors.size} issue(s):",
                )
                errors.forEach { appendLine("- $it") }
            }
        }
        logger.lifecycle(
            "Nullability audit passed: " +
                    "${properties.count { it.nullable }} nullable model " +
                    "properties have current official-first evidence.",
        )
    }

    private fun inventory(): List<ModelProperty> {
        val sourceRoot = repository.resolve(
            "protocol-model/src/commonMain/kotlin",
        )
        return buildList {
            kotlinSources(sourceRoot).forEach { path ->
                val text = path.readText()
                val masked = lexicalMask(text)
                val fileDeclarations = declarations(masked)
                val relative = sourceRoot.relativize(path).toString()
                    .replace('\\', '/')
                fileDeclarations.forEach { declaration ->
                    val constructorStart = declaration.constructorStart
                        ?: return@forEach
                    val constructorEnd = declaration.constructorEnd
                        ?: return@forEach
                    val owner = ownerName(
                        declaration,
                        fileDeclarations,
                    )
                    val start = constructorStart + 1
                    val constructor = masked.substring(
                        start,
                        constructorEnd,
                    )
                    propertyDeclaration.findAll(constructor).forEach { match ->
                        val propertyType = parseType(
                            masked,
                            start + match.range.last + 1,
                            constructorEnd,
                        )
                        if (propertyType.isEmpty()) return@forEach
                        val name = match.groups["name"]!!.value
                        add(
                            ModelProperty(
                                id = "$relative#$owner.$name",
                                source = relative,
                                line = lineNumber(
                                    text,
                                    start + match.range.first,
                                ),
                                owner = owner,
                                name = name,
                                type = propertyType,
                                nullable = propertyType
                                    .trimEnd()
                                    .endsWith('?'),
                                unknownAnnotation =
                                    "@UnknownNullability" in
                                            match.groups["annotations"]!!.value,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun inventoryReport(
        properties: List<ModelProperty>,
    ): JsonObject {
        val bySource = propertiesBySource(properties)
        return jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "property_count" to jsonNumber(properties.size),
            "nullable_count" to
                    jsonNumber(properties.count { it.nullable }),
            "unknown_count" to
                    jsonNumber(properties.count { it.unknownAnnotation }),
            "inventory_sha256" to
                    jsonString(propertySignature(properties)),
            "sources" to JsonArray(
                bySource.map { (source, sourceProperties) ->
                    jsonObjectOf(
                        "source" to jsonString(source),
                        "property_count" to
                                jsonNumber(sourceProperties.size),
                        "nullable_count" to jsonNumber(
                            sourceProperties.count { it.nullable },
                        ),
                        "signature_sha256" to jsonString(
                            propertySignature(sourceProperties),
                        ),
                    )
                },
            ),
            "properties" to JsonArray(
                properties.map { property ->
                    jsonObjectOf(
                        "id" to jsonString(property.id),
                        "source" to jsonString(property.source),
                        "line" to jsonNumber(property.line),
                        "owner" to jsonString(property.owner),
                        "name" to jsonString(property.name),
                        "type" to jsonString(property.type),
                        "nullable" to jsonBoolean(property.nullable),
                        "unknown_annotation" to
                                jsonBoolean(property.unknownAnnotation),
                    )
                },
            ),
        )
    }

    private fun validateLedger(
        properties: List<ModelProperty>,
        ledgerPath: Path,
    ): List<String> {
        val errors = mutableListOf<String>()
        val (document, rows) = runCatching {
            loadLedger(ledgerPath)
        }.getOrElse {
            return listOf(it.message ?: it.toString())
        }
        val specification = ledgerPath.parent
        val snapshot = specification.resolve(
            "wiki-protocol-snapshot.json",
        ).readJsonObject()
        val sourceIndex = specification.resolve(
            "official-source-index.json",
        ).readJsonObject()
        val expectedTarget = mapOf(
            "minecraft_version" to
                    snapshot.requiredString("minecraft_version"),
            "protocol_version" to snapshot.requiredInt("protocol_version"),
            "wiki_revision_id" to snapshot.requiredObject("source")
                .requiredInt("revision_id"),
            "server_sha1" to sourceIndex.requiredString("server_sha1"),
        )
        val target = document["target"].asStringMapOrNull()
        if (
            target == null ||
            expectedTarget.any { (key, value) ->
                target[key].normalizedScalar() != value.normalizedScalar()
            } ||
            target.keys != expectedTarget.keys
        ) {
            errors += "Nullability ledger target does not match the " +
                    "current Wiki/JAR evidence"
        }

        val coverage = document["coverage"].asStringMapOrNull()
            ?: run {
                errors +=
                    "Nullability ledger must contain a coverage mapping"
                emptyMap()
            }
        val expectedInventorySignature = propertySignature(properties)
        if (
            coverage["inventory_sha256"] != expectedInventorySignature
        ) {
            errors += "Nullability coverage inventory hash does not match " +
                    "all current constructor properties"
        }
        if (coverage["property_count"].asIntOrNull() != properties.size) {
            errors += "Nullability coverage property count is stale"
        }
        if (
            coverage["nullable_count"].asIntOrNull() !=
            properties.count { it.nullable }
        ) {
            errors += "Nullability coverage nullable count is stale"
        }
        val sourceCoverage = coverage["sources"].asListOrNull()
            ?: run {
                errors +=
                    "Nullability coverage must contain a sources list"
                emptyList()
            }
        val coverageBySource = linkedMapOf<String, Map<String, Any?>>()
        sourceCoverage.forEach { value ->
            val row = value.asStringMapOrNull()
            val source = row?.get("source") as? String
            if (row == null || source == null) {
                errors += "Nullability source coverage has an entry " +
                        "without a string source"
                return@forEach
            }
            if (coverageBySource.put(source, row) != null) {
                errors +=
                    "Duplicate nullability source coverage: $source"
            }
        }
        val expectedSources = propertiesBySource(properties)
        (expectedSources.keys - coverageBySource.keys).sorted()
            .forEach {
                errors += "Missing all-property nullability coverage: $it"
            }
        (coverageBySource.keys - expectedSources.keys).sorted()
            .forEach {
                errors += "Stale all-property nullability coverage: $it"
            }
        (expectedSources.keys intersect coverageBySource.keys)
            .sorted()
            .forEach { source ->
                val row = coverageBySource.getValue(source)
                val sourceProperties = expectedSources.getValue(source)
                if (
                    row["property_count"].asIntOrNull() !=
                    sourceProperties.size
                ) {
                    errors += "$source: covered property count is stale"
                }
                if (
                    row["signature_sha256"] !=
                    propertySignature(sourceProperties)
                ) {
                    errors += "$source: covered property signature is " +
                            "stale; re-audit every constructor property in " +
                            "this source"
                }
                if (row["verdict"] != "confirmed") {
                    errors +=
                        "$source: all-property coverage is not confirmed"
                }
                if ((row["evidence"] as? String).isNullOrEmpty()) {
                    errors +=
                        "$source: coverage evidence must be non-empty"
                }
            }

        val byId = linkedMapOf<String, Map<String, Any?>>()
        rows.forEach { row ->
            val id = row["id"] as? String
            if (id == null) {
                errors +=
                    "Nullability ledger has an entry without a string id"
            } else if (byId.put(id, row) != null) {
                errors += "Duplicate nullability ledger entry: $id"
            }
        }
        val expectedById = properties
            .filter { it.nullable || it.unknownAnnotation }
            .associateBy { it.id }
        (expectedById.keys - byId.keys).sorted().forEach {
            errors += "Missing nullability evidence: $it"
        }
        (byId.keys - expectedById.keys).sorted().forEach {
            errors += "Stale nullability evidence: $it"
        }
        (expectedById.keys intersect byId.keys).sorted().forEach { propertyId ->
            validatePropertyEvidence(
                propertyId,
                expectedById.getValue(propertyId),
                byId.getValue(propertyId),
                errors,
            )
        }
        return errors
    }

    private fun validatePropertyEvidence(
        propertyId: String,
        property: ModelProperty,
        row: Map<String, Any?>,
        errors: MutableList<String>,
    ) {
        val declared = row["kotlin"]
        val expectedDeclared = if (property.nullable) {
            "nullable"
        } else {
            "non-null"
        }
        if (declared != expectedDeclared) {
            errors += "$propertyId: ledger Kotlin state is '$declared', " +
                    "source is $expectedDeclared"
        }
        val verdict = row["verdict"]
        if (verdict !in setOf("confirmed", "unresolved")) {
            errors += "$propertyId: invalid verdict '$verdict'"
        }
        if ((verdict == "unresolved") != property.unknownAnnotation) {
            errors += "$propertyId: unresolved verdict and " +
                    "@UnknownNullability annotation disagree"
        }
        if (verdict == "unresolved" && !property.nullable) {
            errors +=
                "$propertyId: unresolved properties must be nullable"
        }
        val sources = row["sources"].asStringMapOrNull()
        if (sources == null) {
            errors += "$propertyId: sources must be a mapping"
            return
        }
        sourcePriority.forEach { sourceName ->
            val source = sources[sourceName].asStringMapOrNull()
            if (source == null) {
                errors +=
                    "$propertyId: missing $sourceName evidence"
                return@forEach
            }
            val status = source["status"]
            if (status !in allowedSourceStatus) {
                errors += "$propertyId: invalid $sourceName status " +
                        "'$status'"
            }
            if ((source["evidence"] as? String).isNullOrEmpty()) {
                errors += "$propertyId: $sourceName evidence must " +
                        "be non-empty"
            }
        }
        val decisiveEvidence = sourcePriority.firstNotNullOfOrNull { sourceName ->
            val status = sources[sourceName]
                .asStringMapOrNull()
                ?.get("status") as? String
            status
                ?.takeIf(decisiveSourceStatuses::contains)
                ?.let { sourceName to it }
        }
        when (verdict) {
            "confirmed" -> if (decisiveEvidence?.second != "confirmed") {
                errors += "$propertyId: confirmed verdict requires the " +
                        "highest-priority decisive evidence to confirm it " +
                        "(official -> wiki -> mcprotocollib -> minestom)"
            }

            "unresolved" -> if (decisiveEvidence != null) {
                errors += "$propertyId: unresolved verdict has decisive " +
                        "${decisiveEvidence.first} evidence " +
                        "'${decisiveEvidence.second}'"
            }
        }
    }

    private fun loadLedger(
        path: Path,
    ): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
        check(path.exists()) {
            "Nullability ledger is missing: $path"
        }
        val loaderOptions = LoaderOptions().apply {
            maxAliasesForCollections = 10_000
        }
        val yaml = Yaml(SafeConstructor(loaderOptions))
        val document = yaml.load<Any?>(path.readText())
            .asStringMapOrNull()
            ?: error("Nullability ledger must be a mapping")
        document["properties"].asListOrNull()?.let { properties ->
            return document to properties.map {
                it.asStringMapOrNull()
                    ?: error(
                        "Nullability properties must be mappings",
                    )
            }
        }
        val groups = document["evidence_groups"].asListOrNull()
            ?: error(
                "Nullability ledger must contain properties or " +
                        "evidence_groups",
            )
        val rows = mutableListOf<Map<String, Any?>>()
        val seenGroupNames = mutableSetOf<String>()
        groups.forEach { value ->
            val group = value.asStringMapOrNull()
                ?: error(
                    "Nullability evidence groups must be mappings",
                )
            val name = group["name"] as? String
            require(!name.isNullOrEmpty()) {
                "Every nullability evidence group needs a name"
            }
            require(seenGroupNames.add(name)) {
                "Duplicate nullability evidence group: $name"
            }
            val ids = group["ids"].asListOrNull()
            require(!ids.isNullOrEmpty()) {
                "Nullability evidence group '$name' needs a " +
                        "non-empty ids list"
            }
            val shared = group.filterKeys {
                it != "name" && it != "ids"
            }
            ids.forEach { idValue ->
                val id = idValue as? String
                require(!id.isNullOrEmpty()) {
                    "Nullability evidence group '$name' has an invalid id"
                }
                rows += linkedMapOf<String, Any?>("id" to id) + shared
            }
        }
        return document to rows
    }

    private fun lexicalMask(text: String): String {
        val result = text.toCharArray()
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("//", index) -> {
                    val end = text.indexOf('\n', index)
                        .takeIf { it >= 0 }
                        ?: text.length
                    blank(result, index, end)
                    index = end
                }

                text.startsWith("/*", index) -> {
                    val closing = text.indexOf("*/", index + 2)
                    val end = if (closing < 0) {
                        text.length
                    } else {
                        closing + 2
                    }
                    blank(result, index, end)
                    index = end
                }

                text.startsWith("\"\"\"", index) -> {
                    val closing = text.indexOf("\"\"\"", index + 3)
                    val end = if (closing < 0) {
                        text.length
                    } else {
                        closing + 3
                    }
                    blank(result, index, end)
                    index = end
                }

                text[index] == '"' || text[index] == '\'' -> {
                    val quote = text[index]
                    var end = index + 1
                    while (end < text.length) {
                        if (text[end] == '\\') {
                            end += 2
                            continue
                        }
                        end++
                        if (text[end - 1] == quote) break
                    }
                    blank(result, index, end)
                    index = end
                }

                else -> index++
            }
        }
        return result.concatToString()
    }

    private fun blank(chars: CharArray, start: Int, end: Int) {
        for (index in start until minOf(end, chars.size)) {
            if (chars[index] != '\r' && chars[index] != '\n') {
                chars[index] = ' '
            }
        }
    }

    private fun declarations(masked: String): List<ClassDeclaration> =
        classDeclaration.findAll(masked).map { match ->
            var cursor = match.range.last + 1
            var angleDepth = 0
            var constructorStart: Int? = null
            var bodyStart: Int? = null
            while (cursor < masked.length) {
                val character = masked[cursor]
                when {
                    character == '<' -> angleDepth++
                    character == '>' && angleDepth > 0 -> angleDepth--
                    angleDepth == 0 && character == '(' -> {
                        constructorStart = cursor
                        break
                    }

                    angleDepth == 0 && character == '{' -> {
                        bodyStart = cursor
                        break
                    }

                    angleDepth == 0 &&
                            character in charArrayOf('\n', ';', '=') -> break
                }
                cursor++
            }
            val constructorEnd = constructorStart?.let {
                matchingDelimiter(masked, it, '(', ')')
            }
            if (constructorEnd != null) {
                cursor = constructorEnd + 1
                while (cursor < masked.length) {
                    val character = masked[cursor]
                    if (character == '{') {
                        bodyStart = cursor
                        break
                    }
                    if (character in charArrayOf('\n', ';', '=')) break
                    cursor++
                }
            }
            ClassDeclaration(
                name = match.groups["name"]!!.value,
                start = match.range.first,
                constructorStart = constructorStart,
                constructorEnd = constructorEnd,
                bodyStart = bodyStart,
                bodyEnd = bodyStart?.let {
                    matchingDelimiter(masked, it, '{', '}')
                },
            )
        }.toList()

    private fun matchingDelimiter(
        masked: String,
        start: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        for (index in start until masked.length) {
            when (masked[index]) {
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun ownerName(
        declaration: ClassDeclaration,
        declarations: List<ClassDeclaration>,
    ): String {
        val parents = declarations.filter { candidate ->
            candidate.bodyStart != null &&
                    candidate.bodyEnd != null &&
                    candidate.start < declaration.start &&
                    candidate.bodyStart < declaration.start &&
                    declaration.start < candidate.bodyEnd
        }.sortedBy { it.start }
        return (parents.map { it.name } + declaration.name)
            .joinToString(".")
    }

    private fun parseType(
        masked: String,
        start: Int,
        end: Int,
    ): String {
        var angle = 0
        var square = 0
        var round = 0
        var cursor = start
        while (cursor < end) {
            when (masked[cursor]) {
                '<' -> angle++
                '>' -> angle = maxOf(0, angle - 1)
                '[' -> square++
                ']' -> square = maxOf(0, square - 1)
                '(' -> round++
                ')' -> {
                    if (round > 0) {
                        round--
                    } else if (angle == 0 && square == 0) {
                        break
                    }
                }

                ',', '=' -> if (
                    angle == 0 && square == 0 && round == 0
                ) {
                    break
                }
            }
            cursor++
        }
        return masked.substring(start, cursor)
            .trim()
            .split(Regex("""\s+"""))
            .filter(String::isNotEmpty)
            .joinToString(" ")
    }

    private fun propertySignature(
        properties: List<ModelProperty>,
    ): String = properties.joinToString("\n") {
        listOf(
            it.id,
            it.owner,
            it.name,
            it.type,
            if (it.nullable) "nullable" else "non-null",
            if (it.unknownAnnotation) "unknown" else "known",
        ).joinToString("|")
    }.toByteArray(StandardCharsets.UTF_8).sha256()

    private fun propertiesBySource(
        properties: List<ModelProperty>,
    ): Map<String, List<ModelProperty>> {
        val result = linkedMapOf<String, MutableList<ModelProperty>>()
        properties.forEach {
            result.getOrPut(it.source) { mutableListOf() } += it
        }
        return result
    }

    private data class ModelProperty(
        val id: String,
        val source: String,
        val line: Int,
        val owner: String,
        val name: String,
        val type: String,
        val nullable: Boolean,
        val unknownAnnotation: Boolean,
    )

    private data class ClassDeclaration(
        val name: String,
        val start: Int,
        val constructorStart: Int?,
        val constructorEnd: Int?,
        val bodyStart: Int?,
        val bodyEnd: Int?,
    )

    private companion object {
        val classDeclaration = Regex(
            """\b(?:data\s+|value\s+|enum\s+|annotation\s+)?""" +
                    """(?:class|interface|object)\s+""" +
                    """(?<name>[A-Za-z_][A-Za-z0-9_]*)""",
        )
        val propertyDeclaration = Regex(
            """(?<annotations>(?:@[A-Za-z_][A-Za-z0-9_.]*""" +
                    """(?:\s*\([^)]*\))?\s*)*)""" +
                    """(?:(?:public|internal|private|protected|override)\s+)*""" +
                    """(?:val|var)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*:""",
        )
        val allowedSourceStatus = setOf(
            "confirmed",
            "contradicts",
            "ambiguous",
            "absent",
            "version-unavailable",
        )
        val sourcePriority = listOf(
            "official",
            "wiki",
            "mcprotocollib",
            "minestom",
        )
        val decisiveSourceStatuses = setOf(
            "confirmed",
            "contradicts",
        )
    }
}

private fun Any?.asStringMapOrNull(): Map<String, Any?>? {
    val map = this as? Map<*, *> ?: return null
    if (map.keys.any { it !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return map as Map<String, Any?>
}

private fun Any?.asListOrNull(): List<Any?>? =
    this as? List<Any?>

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Int -> this
    is Long -> toInt().takeIf { it.toLong() == this }
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}

private fun Any?.normalizedScalar(): Any? = when (this) {
    is Number -> toLong()
    else -> this
}
