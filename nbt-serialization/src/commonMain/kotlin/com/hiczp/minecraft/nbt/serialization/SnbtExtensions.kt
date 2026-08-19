package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag

/** Encodes this tag with [format]. */
fun NbtTag.toSnbtString(format: SnbtFormat = SnbtFormat): String =
    format.encodeTagToString(this)

/** Encodes this compound-root document with [format]. */
fun NbtDocument.toSnbtString(format: SnbtFormat = SnbtFormat): String =
    format.encodeDocumentToString(this)

/** Parses this complete SNBT string as a tag with [format]. */
fun String.toNbtTag(format: SnbtFormat = SnbtFormat): NbtTag =
    format.decodeTagFromString(this)

/** Parses this complete SNBT string as a compound-root document with [format]. */
fun String.toNbtDocument(format: SnbtFormat = SnbtFormat): NbtDocument =
    format.decodeDocumentFromString(this)
