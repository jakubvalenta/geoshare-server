package net.geoshare_app

import io.ktor.client.call.body
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.geoshare_app.lib.CallDetails
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.base64Decode
import net.geoshare_app.lib.base64Encode
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.lib.sign
import net.geoshare_app.testing.CertLists
import net.geoshare_app.testing.Certs
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import net.geoshare_app.testing.jsonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationTest {
    @Test
    fun `challenge route - always returns challenge of 32 bytes`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        val res = jsonHttpClient.post("/v1/auth/challenge")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(32, res.body<ChallengeResponse>().challenge.base64Decode().size)

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:challenge:success:$hour:total"))
    }

    @Test
    fun `register route - when challenge is not found in cache, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Register
        val registrationChallenge = "spam".toByteArray()
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when challenge has been used, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        // Register 2
        val res2 = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when challenge expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val cache = FakeCache(testScheduler.timeSource)
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            advanceTimeBy(3.seconds)

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge.base64Encode(),
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid challenge", res.body())

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
        }
    }

    @Test
    fun `register route - when certificate chain is invalid, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.intermediateKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.noLeaf
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Extension parsing failure", res.body())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when signature is invalid, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.intermediateKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when certificate has been revoked, it returns 401`() = runTest {
        testApplication {
            val cache = FakeCache(testScheduler.timeSource)
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.revoked
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge.base64Encode(),
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Path validation failure chain", res.body())

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
        }
    }

    @Test
    fun `register route - when signature is valid, it returns token`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallenge.base64Encode(),
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
    }

    @Test
    fun `register route - when signature is valid with self-signed key extension with unknown boot key, it returns token with unverified device`() =
        testApplication {
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.selfSigned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge.base64Encode(),
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
        }

    @Test
    fun `login route - when challenge is not found, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Login
        val loginChallenge = "spam".toByteArray()
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonHttpClient.post("/v1/auth/login") {
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

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when challenge has been used, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
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
        val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonHttpClient.post("/v1/auth/login") {
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
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        // Login again
        val res2 = jsonHttpClient.post("/v1/auth/login") {
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

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when challenge expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val cache = FakeCache(testScheduler.timeSource)
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
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
            val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            advanceTimeBy(3.seconds)

            // Login
            val loginSignature = Certs.leafKey.private.sign(loginChallenge)
            val publicKey = Certs.leafKey.public
            val res = jsonHttpClient.post("/v1/auth/login") {
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

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        }
    }

    @Test
    fun `login route - when device is not found, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Login challenge
        val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonHttpClient.post("/v1/auth/login") {
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

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when device expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val cache = FakeCache(testScheduler.timeSource)
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge.base64Encode(),
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            advanceTimeBy(7.seconds)

            // Login challenge
            val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Login
            val loginSignature = Certs.leafKey.private.sign(loginChallenge)
            val publicKey = Certs.leafKey.public
            val res = jsonHttpClient.post("/v1/auth/login") {
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

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        }
    }

    @Test
    fun `login route - when signature is invalid, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
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
        val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.intermediateKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonHttpClient.post("/v1/auth/login") {
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

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when signature is valid, it returns token`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // Registration challenge
        val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Register
        val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
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
        val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge.base64Decode()

        // Login
        val loginSignature = Certs.leafKey.private.sign(loginChallenge)
        val publicKey = Certs.leafKey.public
        val res = jsonHttpClient.post("/v1/auth/login") {
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
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = CallDetails.formatCurrentHour()
        assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
    }

    @Test
    fun `login route - when signature is valid and device is unverified, it returns token with unverified device`() =
        testApplication {
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.selfSigned
            jsonHttpClient.post("/v1/auth/register") {
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
            val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Login
            val loginSignature = Certs.leafKey.private.sign(loginChallenge)
            val publicKey = Certs.leafKey.public
            val res = jsonHttpClient.post("/v1/auth/login") {
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
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(Certs.leafKey.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            val hour = CallDetails.formatCurrentHour()
            assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
        }

    @Test
    fun `login route - when signature is valid, it refreshes device expiration`() = runTest {
        testApplication(testScheduler) {
            val cache = FakeCache(testScheduler.timeSource)
            val statsRepository = StatsRepository(cache)
            environment {
                config = ApplicationConfig("application-test.conf")
            }
            application {
                rootModule(statsRepository)
                authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            }

            // Registration challenge
            val registrationChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Register
            val registrationSignature = Certs.leafKey.private.sign(registrationChallenge)
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge.base64Encode(),
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            // Advance time, so the device almost expires
            advanceTimeBy(5.seconds)

            // Login challenge
            val loginChallenge = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Login
            val loginSignature = Certs.leafKey.private.sign(loginChallenge)
            val publicKey = Certs.leafKey.public
            val res = jsonHttpClient.post("/v1/auth/login") {
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

            // Advance time, so the device would expire if the expiration wasn't refreshed
            advanceTimeBy(5.seconds)

            // Login challenge
            val loginChallenge2 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge.base64Decode()

            // Login
            val loginSignature2 = Certs.leafKey.private.sign(loginChallenge2)
            val res2 = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallenge2.base64Encode(),
                        signature = loginSignature2.base64Encode(),
                        publicKey = publicKey.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res2.status)

            val hour = CallDetails.formatCurrentHour()
            assertEquals(2, statsRepository.get("stats:auth:login:success:$hour:total"))
        }
    }

    @Test
    fun `status challenge success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(100) {
            statsRepository.increase("stats:auth:challenge:success:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/challenge/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:challenge:success:$hour:total")
        val resFailure = client.head("/v1/status/auth/challenge/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status challenge success route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/challenge/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status login success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(10) {
            statsRepository.increase("stats:auth:login:success:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/login/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:login:success:$hour:total")
        val resFailure = client.head("/v1/status/auth/login/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status login error route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/login/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status login error route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(10) {
            statsRepository.increase("stats:auth:login:error:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/login/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:login:error:$hour:total")
        val resFailure = client.head("/v1/status/auth/login/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status login success route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/login/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status register success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(10) {
            statsRepository.increase("stats:auth:register:success:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/register/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:register:success:$hour:total")
        val resFailure = client.head("/v1/status/auth/register/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status register success route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/register/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status register error route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(10) {
            statsRepository.increase("stats:auth:register:error:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/register/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:register:error:$hour:total")
        val resFailure = client.head("/v1/status/auth/register/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status register error route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/register/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status unauthorized route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(100) {
            statsRepository.increase("stats:auth:unauthorized:$hour:total")
        }
        val resSuccess = client.head("/v1/status/auth/unauthorized/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:auth:unauthorized:$hour:total")
        val resFailure = client.head("/v1/status/auth/unauthorized/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status unauthorized route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/auth/unauthorized/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }
}
