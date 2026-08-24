package com.hiczp.minecraft.world.io.internal.admzip

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

internal external interface AdmZip {
    fun getEntries(): Array<AdmZipEntry>

    fun addFile(
        entryName: String,
        content: Uint8Array,
    ): AdmZipEntry

    fun toBuffer(): Uint8Array
}

// adm-zip is a CommonJS module whose module.exports value is the constructor itself. Kotlin's typed @JsModule
// declarations address named exports, so keep this unavoidable interop bridge here while adm-zip owns ZIP behavior.
internal fun createAdmZip(): AdmZip = js("new (require('adm-zip'))()")

internal fun createAdmZip(input: Uint8Array): AdmZip =
    js("new (require('adm-zip'))(require('buffer').Buffer.from(input.buffer, input.byteOffset, input.byteLength))")

// Node Buffers are Uint8Array views into pooled ArrayBuffers. Khronos's view conversion keeps the entire backing
// buffer, so copy the exact visible range when crossing back into a Kotlin ByteArray.
internal fun ByteArray.toExactUint8Array(): Uint8Array = Uint8Array(size).also { target ->
    indices.forEach { index -> target[index] = this[index] }
}

internal fun Uint8Array.toExactByteArray(): ByteArray = ByteArray(length) { index -> this[index] }

internal external interface AdmZipEntry {
    val entryName: String
    val isDirectory: Boolean
    val header: AdmZipEntryHeader

    fun getData(): Uint8Array
}

internal external interface AdmZipEntryHeader {
    val size: Double
}
