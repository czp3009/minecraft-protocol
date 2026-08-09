@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.hiczp.minecraft.protocol.transport

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray

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
    override fun process(input: ByteArray): ByteArray {
        val source = Buffer().apply { write(input) }
        transform.write(source, source.size)
        return output.readByteArray()
    }
}
