package net.geoshare_app

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
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

        // Register
        val registrationChallenge = "spam".toByteArray()
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())
    }

    @Test
    fun `register route when certificate chain is invalid returns 401`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.intermediateKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.noLeaf
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Extension parsing failure", res.body())
    }

    @Test
    fun `register route when signature is invalid returns 401`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.intermediateKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())
    }

    @Test
    fun `register route when challenge has been used returns 401`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            Certs.leafKey.public.fingerprint(),
            Tokens.verify(res.body<TokenResponse>().token).subject,
        )

        // Register 2
        val res2 = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())
    }

    @Test
    fun `register route when signature is valid returns token`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
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
    fun `login route when challenge is not found returns 401`() = testApplication {
        configure("application-test.conf")

        // Login
        val loginChallenge = "spam".toByteArray()
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())
    }

    @Test
    fun `login route when device is not found returns 401`() = testApplication {
        configure("application-test.conf")

        // Login challenge
        val loginChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Unknown device", res.body())
    }

    @Test
    fun `login route when signature is invalid returns 401`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.intermediateKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())
    }


    @Test
    fun `login route when challenge has been used returns 401`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            Certs.leafKey.public.fingerprint(),
            Tokens.verify(res.body<TokenResponse>().token).subject,
        )

        // Login again
        val res2 = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())
    }

    @Test
    fun `login route when signature is valid returns token`() = testApplication {
        configure("application-test.conf")

        // Registration challenge
        val registrationChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallenge = jsonClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallenge.base64Encode(),
                    signature = loginSignature.base64Encode(),
                    publicKey = publicKey.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            Certs.leafKey.public.fingerprint(),
            Tokens.verify(res.body<TokenResponse>().token).subject,
        )
    }

    // TODO Test device expiration
    // TODO Test device refresh
    // TODO Test token expiration
}
