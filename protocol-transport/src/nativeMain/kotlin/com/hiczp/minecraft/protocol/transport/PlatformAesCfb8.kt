@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.hiczp.minecraft.protocol.transport

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.io.Buffer
import kotlinx.io.RawSink

internal actual fun platformAesCfb8Cipher(
    key: ByteArray,
    initializationVector: ByteArray,
    decrypting: Boolean,
): MinecraftStreamCipher {
    val aes = CryptographyProvider.Default.get(AES.CFB8)
    val decodedKey = aes.keyDecoder().decodeFromByteArrayBlocking(
        AES.Key.Format.RAW,
        key,
    )
    val output = Buffer()
    val cipher = decodedKey.cipher()
    val transform = if (decrypting) {
        cipher.decryptingSinkWithIv(initializationVector, output)
    } else {
        cipher.encryptingSinkWithIv(initializationVector, output)
    }
    // cryptography-kotlin exposes stateful CFB8 as a sink. Keep that one sink
    // alive across process calls and drain only newly produced bytes so the
    // common incremental cipher contract preserves feedback state.
    return CryptographyAesCfb8Cipher(transform, output)
}

private class CryptographyAesCfb8Cipher(
    private val transform: RawSink,
    private val output: Buffer,
) : MinecraftStreamCipher {
    private val inputBuffer = Buffer()

    override fun process(
        input: ByteArray,
        startIndex: Int,
        endIndex: Int,
        output: ByteArray,
        outputStartIndex: Int,
    ): Int {
        val byteCount = endIndex - startIndex
        inputBuffer.write(input, startIndex, endIndex)
        transform.write(inputBuffer, byteCount.toLong())
        var written = 0
        while (written < byteCount) {
            val count = this.output.readAtMostTo(
                output,
                startIndex = outputStartIndex + written,
                endIndex = outputStartIndex + byteCount,
            )
            check(count >= 0) { "AES/CFB8 transform did not produce enough bytes" }
            written += count
        }
        return written
    }
}
