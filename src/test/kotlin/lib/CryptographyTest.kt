package net.geoshare_app.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptographyTest {
    private val publicKeyFingerprint = "test-fingerprint".toByteArray().base64Encode()
    private val challenge = "test-challenge".toByteArray()

    @Test
    fun `buildSigningPayloadV1 - returns payload for all signing purposes`() {
        SigningPurpose.entries.forEach { purpose ->
            when (purpose) {
                SigningPurpose.LOGIN -> assertEquals(
                    "geoshare-server:v1:login:dGVzdC1maW5nZXJwcmludA==:dGVzdC1jaGFsbGVuZ2U=",
                    buildSigningPayloadV1(purpose, publicKeyFingerprint, challenge),
                )

                SigningPurpose.REGISTRATION -> assertEquals(
                    "geoshare-server:v1:registration:dGVzdC1maW5nZXJwcmludA==:dGVzdC1jaGFsbGVuZ2U=",
                    buildSigningPayloadV1(purpose, publicKeyFingerprint, challenge),
                )
            }
        }
    }

    @Test
    fun `buildSigningPayloadV1 - when fingerprint contains separator, it throws an exception`() {
        assertFailsWith<IllegalArgumentException> {
            buildSigningPayloadV1(SigningPurpose.REGISTRATION, "fingerprint:with:colon", challenge)
        }
    }

    @Test
    fun `buildSigningPayloadV1 - when challenge contains separator, it does not throw an exception`() {
        val challenge = "challenge:with:colon".toByteArray()
        assertEquals(
            "geoshare-server:v1:registration:dGVzdC1maW5nZXJwcmludA==:${challenge.base64Encode()}",
            buildSigningPayloadV1(SigningPurpose.REGISTRATION, publicKeyFingerprint, challenge),
        )
    }
}
