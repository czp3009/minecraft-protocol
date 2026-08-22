package com.hiczp.minecraft.protocol.transport

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformAesCfb8Cipher(
    key: ByteArray,
    initializationVector: ByteArray,
    decrypting: Boolean,
): MinecraftStreamCipher {
    val cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(
            if (decrypting) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(initializationVector),
        )
    }
    return object : MinecraftStreamCipher {
        // Minecraft encrypts one continuous connection stream. Reuse update
        // instead of finalizing per packet so JCA retains CFB8 feedback state.
        override fun process(
            input: ByteArray,
            startIndex: Int,
            endIndex: Int,
            output: ByteArray,
            outputStartIndex: Int,
        ): Int = cipher.update(
            input,
            startIndex,
            endIndex - startIndex,
            output,
            outputStartIndex,
        )
    }
}
