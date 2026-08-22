@file:OptIn(ExperimentalUnsignedTypes::class)

package com.hiczp.minecraft.protocol.transport

import com.hiczp.minecraft.protocol.transport.internal.nodecrypto.NodeCipher
import com.hiczp.minecraft.protocol.transport.internal.nodecrypto.createCipheriv
import com.hiczp.minecraft.protocol.transport.internal.nodecrypto.createDecipheriv
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array

internal actual fun platformAesCfb8Cipher(
    key: ByteArray,
    initializationVector: ByteArray,
    decrypting: Boolean,
): MinecraftStreamCipher {
    // Node's crypto ABI consumes Uint8Array; these are representation-only
    // Kotlin/Web conversions and Node still performs the complete AES/CFB8
    // transform.
    val keyBytes = key.asUByteArray().toUint8Array()
    val ivBytes = initializationVector.asUByteArray().toUint8Array()
    val cipher = if (decrypting) {
        createDecipheriv(AES_128_CFB8, keyBytes, ivBytes)
    } else {
        createCipheriv(AES_128_CFB8, keyBytes, ivBytes)
    }
    return NodeAesCfb8Cipher(cipher)
}

private class NodeAesCfb8Cipher(
    private val cipher: NodeCipher,
) : MinecraftStreamCipher {
    // Keep the Node Cipher instance alive and call update only; finalizing at a
    // packet boundary would reset Minecraft's continuous CFB8 feedback stream.
    override fun process(
        input: ByteArray,
        startIndex: Int,
        endIndex: Int,
        output: ByteArray,
        outputStartIndex: Int,
    ): Int {
        val selected = if (startIndex == 0 && endIndex == input.size) {
            input
        } else {
            input.copyOfRange(startIndex, endIndex)
        }
        val transformed = cipher.update(selected.asUByteArray().toUint8Array())
            .toUByteArray()
            .asByteArray()
        transformed.copyInto(output, outputStartIndex)
        return transformed.size
    }
}

private const val AES_128_CFB8 = "aes-128-cfb8"
