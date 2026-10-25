package com.hiczp.minecraft.demo.nbtreader

import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.serialization.SerializationException
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException as NbtIOException

/** Reads one standalone Java Edition NBT file and prints a tree or the complete root value as SNBT. */
fun main(args: Array<String>) {
    val argParser = ArgParser("nbt-reader")
    val file by argParser.argument(ArgType.String, description = "Raw or GZIP Java Edition NBT file")
    val format by argParser.option(
        ArgType.Choice<OutputFormat>(),
        description = "Output format",
    ).default(OutputFormat.TREE)
    val compression by argParser.option(
        ArgType.Choice<InputCompression>(),
        description = "Input compression; auto detects the GZIP header",
    ).default(InputCompression.AUTO)
    val depth by argParser.option(ArgType.Int, description = "Tree expansion depth; root is depth zero").default(3)
    val limit by argParser.option(ArgType.Int, description = "Tree entries or array elements shown per container")
        .default(16)
    val full by argParser.option(ArgType.Boolean, description = "Show the full tree without preview limits")
        .default(false)
    argParser.parse(args)

    try {
        val nbtTreePrinter = NbtTreePrinter(depth, limit, full, ::printOutputLine)
        val nbtReadResult = NbtReader().read(file.toPath(), compression)
        when (format) {
            OutputFormat.SNBT -> printOutputLine(SnbtFormat.encodeTagToString(nbtReadResult.namedNbtTag.nbtTag))
            OutputFormat.TREE -> {
                printOutputLine("File: ${file.quotedPreview(full)}")
                printOutputLine("Compression: ${nbtReadResult.inputCompression.name.lowercase()}")
                printOutputLine("Root name: ${nbtReadResult.namedNbtTag.name.quotedPreview(full)}")
                nbtTreePrinter.print(nbtReadResult.namedNbtTag.nbtTag)
            }
        }
    } catch (exception: Exception) {
        when (exception) {
            is CancellationException -> throw exception
            is IOException, is NbtIOException, is SerializationException, is IllegalArgumentException ->
                exitWithError("nbt-reader: ${exception.message ?: "Unable to read NBT"}")

            else -> throw exception
        }
    }
}

internal expect fun exitWithError(message: String): Nothing

/** Writes UTF-8 output even when the host's default console encoding cannot represent the value. */
internal expect fun printOutputLine(message: String)

private enum class OutputFormat { TREE, SNBT }
