package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag

/** Encodes this tag with [snbtFormat]. */
fun NbtTag.toSnbtString(snbtFormat: SnbtFormat = SnbtFormat): String =
    snbtFormat.encodeTagToString(this)

/** Encodes this compound-root document with [snbtFormat]. */
fun NbtDocument.toSnbtString(snbtFormat: SnbtFormat = SnbtFormat): String =
    snbtFormat.encodeDocumentToString(this)

/** Parses this complete SNBT string as a tag with [snbtFormat]. */
fun String.toNbtTag(snbtFormat: SnbtFormat = SnbtFormat): NbtTag =
    snbtFormat.decodeTagFromString(this)

/** Parses this complete SNBT string as a compound-root document with [snbtFormat]. */
fun String.toNbtDocument(snbtFormat: SnbtFormat = SnbtFormat): NbtDocument =
    snbtFormat.decodeDocumentFromString(this)
