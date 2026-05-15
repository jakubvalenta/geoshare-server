package net.geoshare_app

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import junit.framework.TestCase.assertTrue
import net.geoshare_app.testing.CertLists
import net.geoshare_app.testing.Certs
import net.geoshare_app.testing.Tokens
import net.geoshare_app.testing.jsonClient
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticationTest {
    @Test
    fun `challenge route always returns challenge of 32 bytes`() = testApplication {
        configure("application-test.conf")
        val res = jsonClient.post("/v1/auth/challenge")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(32, res.body<ChallengeResponse>().challenge.base64Decode().size)
    }

    @Test
    fun `register route when challenge is not found in cache returns 401`() = testApplication {
        configure("application-test.conf")
        val challenge = "spam".toByteArray()
        val signature = Certs.leafKey.private.sign(challenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = challenge.base64Encode(),
                    signature = signature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())
    }

    @Test
    fun `register route when certificate chain is valid returns token`() = testApplication {
        configure("application-test.conf")
        val challenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()
        val signature = Certs.leafKey.private.sign(challenge)
        val verified = Certs.leafKey.public.verifySignature(signature, challenge)
        assertTrue(verified)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = challenge.base64Encode(),
                    signature = signature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            Certs.leafKey.public.fingerprint(),
            Tokens.verify(res.body<TokenResponse>().token).subject,
        )
    }

    @Test
    fun `register route when certificate chain is invalid returns 401`() = testApplication {
        configure("application-test.conf")
        val challenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()
        val signature = Certs.intermediateKey.private.sign(challenge)
        val certificateChain = CertLists.noLeaf
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = challenge.base64Encode(),
                    signature = signature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Extension parsing failure", res.body())
    }
}
