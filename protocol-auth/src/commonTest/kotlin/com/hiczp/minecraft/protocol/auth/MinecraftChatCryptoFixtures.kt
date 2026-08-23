package com.hiczp.minecraft.protocol.auth

import kotlin.io.encoding.Base64

internal object MinecraftChatCryptoFixtures {
    const val CHAT_PAYLOAD_SHA256 = "50efdf935f3aeeb53a5825bccb09caf0099fb4e0320b514e923f9b91159ce907"
    const val CREDENTIAL_PAYLOAD_SHA256 = "0803db96561c486fcb566e4e8f490e9d8c86e39b94c17c8d8fdeaf4231c85daf"

    val privateKeyBase64 = listOf(
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDFiRcDLaDbPm9UsHO2wtCmcmA2gLjCGuWjYGFDgK36G1f5",
        "76OR/MlbpoGANNeAzKYg4NI2CosWLZU35oTq2Wbt5cH82Ol3ar73C3aAucOuVM6+bU8BxKOcTgCZXHYpOmyWbw3dpbB007Iq",
        "u7PZeXej6takcVC7xhURnzvhoMN7BaVJvK3fDP9o8YyBWFTbHSQIKcUO2UdZ5+7DCqTE+en2t8ugHuApc5KWdz099xDTtoyg",
        "4nwrJurkAsbKfw2qwrAmVs0R+HLVUrRpxhbH8HbpoYZQ+AXJlSqwLQTQy9YcE5Gw2vgTA0oDpb8u7NIzXzEXk4wEsEj9ihOJ",
        "da8sSuOPAgMBAAECggEAOmteyOvXpL+EQXGl5ykad/9fgP70pUM7IuRAH6yQx2UK0boTj/tIubg2mGoISek8QID86kqX88kr",
        "VkrwiFwfWsAWbObhtRV2wK09MLi/rHEca6j8MrOrB3DyMGjt8Sd8MclKOoDj/MkW6hh1Ch4oSewL6rowfDgKxxlmpkXbSihN",
        "oMx+GgoUyuj1JfzYAwcLcBwg9ztdINHjoFytMn25OW4/gITwO81MNzzikifzqkggwD3jyUIbdn8kZmTTHAtmRnWGxTWutNF/",
        "UqMxrrAR83YJ9UtQLIDc1TIcqCYavCOVF7qOJ7vgO5tEN2O1HNsLGFRY4VulwfomAGJ08zPe8QKBgQD4DIyd7FgwZgTbB0i5",
        "wnVDe5TsZKhA15YDWyRSS5oHWhNPWievtqwLBkWRtB08wD7E120domcXUWR6sPnFb/e/kMaHjrZUBebThENq+PkbeGQCrvcb",
        "KF/9jVhWK8oYZ/nE3agWLLdG/961Hpx4O2y6L8Viuq5vCqnwXWOEtoil0wKBgQDL3gjmyNWHA5aaFf8K+IvKXuMTvXHR2+kY",
        "C8g9Z6nawEI0Ym++Pbcf85w8XXb0aA41F7HqPBB3PWr8K5K8DbGF6s+7y4b2VXsytLZ4HQeRfwebNHcH2TkRejrPNl2NYoyx",
        "z20GsZHUlPEEYCUTMSLi2cJdGGP5GTTvEn50dNmJ1QKBgCDF77JR7tj+MbgKv1Yj1kCDTIrrRbvDgEOTQDpLWN+NzC1Y2ROD",
        "TDnsqzZ0GMTVFbYGTJl0wrA3BdKcHXQztgUuiLySY464dOYPfKTennM9teEQ4v9Il1411a0U/g+5dSvIqZO8dr6/wdomYEAW",
        "gegbtbW0uqVbQFbM0ABR6b4XAoGBAI+WeDCMPZCVn2oxmevROSw0/rz1jogf2qH8EnHlZIXVKgwZVNjqQOO5Qk5mChWEgJU2",
        "djIjUfmaAZNQ4U2gW2uWAfAkHo+7j0UccPRShfUdQm83WarmfUJpE6jEgsnFIpFOJl0zjiHrMYJCcXp9/jNG6pWFS0y0S0sj",
        "fHtrnZhtAoGAI7rw8AB7FhxqelMmADXvDfRDFd0AMqHJ3XTz4By71TZeiZWjz94CcjmVqA1fYy2DIMihGPeTvs45lzN89pii",
        "KMvHKiv78JsyZHePRS/ngoUQiQ25OxJHLTcKT8VL8wufR0MdzJLCpHJ/PutQ0SQxYcMYOImxOGgXpt2/H7wgrHk=",
    ).joinToString(separator = "")

    val publicKeyBase64 = listOf(
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxYkXAy2g2z5vVLBztsLQpnJgNoC4whrlo2BhQ4Ct+htX+e+jkfzJ",
        "W6aBgDTXgMymIODSNgqLFi2VN+aE6tlm7eXB/Njpd2q+9wt2gLnDrlTOvm1PAcSjnE4AmVx2KTpslm8N3aWwdNOyKruz2Xl3",
        "o+rWpHFQu8YVEZ874aDDewWlSbyt3wz/aPGMgVhU2x0kCCnFDtlHWefuwwqkxPnp9rfLoB7gKXOSlnc9PfcQ07aMoOJ8Kybq",
        "5ALGyn8NqsKwJlbNEfhy1VK0acYWx/B26aGGUPgFyZUqsC0E0MvWHBORsNr4EwNKA6W/LuzSM18xF5OMBLBI/YoTiXWvLErj",
        "jwIDAQAB",
    ).joinToString(separator = "")

    val chatSignatureBase64 = listOf(
        "NOCyiTLhyYLnsOdrwQNEj6Dv8DBFDYZxKFvmf74Vcd99z2cadqeR52Z3c7Py49zrgFlpgLbjPMfHqReyPLRX+EY78pFH6Exx",
        "1J5qnsaI79SUgh48sAm9opcmHbRVV2NRiv3UXrSAsd/qnDaNYtfC7ufQ31Tpd3ARHcOR+sMJ035fbQe5COuAzk6s66eNL/9P",
        "9I7uxNxJkLX/e0pRp81S3BOQl7xNCFmTUqw6ZrfuukpjURqyP2EjTUcYn7x9RWywTkaz9Lq0zrB23rXND9Q2/TCtzGCgOxdR",
        "T6/YvKmYveA2poyboJFrSdFpGXaQbUuieXYpagCqqnKdVccp4Uh90w==",
    ).joinToString(separator = "")

    val credentialSignatureBase64 = listOf(
        "DoyCGYOGtd93fj1NS2mB7qgkS4UFTh51e0hta6ZUnDcBTqFR26gZgPJ6HAfcgDYsf8CMqaHeloRZ7Zcle9sMK1SIe3pktA+Z",
        "Z3z7aJ1JCKQiAizzqjPOvLUDjggVeT4hRBzihSDVN4G0w0dT1oD+U/wNHiPoUQe8w/oMOLIin8M3g6++WJu7IdaIeIzhGNS3",
        "EAQFsYbs3wWY+/mncdEGIVvCJJfANEwgiJjmV+DX2ZmOWXRbzCOtImfdUTTeVyONNYJqFXssYSyMRhnlV9uQ+WwGJnUqSlrm",
        "S6dy/DgWB88NbgVqafbdNhTQxCzpzr64/NqB9op6Y8YcNkcoprWi5Q==",
    ).joinToString(separator = "")

    fun privateKey(): ByteArray = Base64.Default.decode(privateKeyBase64)

    fun publicKey(): ByteArray = Base64.Default.decode(publicKeyBase64)

    fun chatSignature(): ByteArray = Base64.Default.decode(chatSignatureBase64)

    fun credentialSignature(): ByteArray = Base64.Default.decode(credentialSignatureBase64)
}
