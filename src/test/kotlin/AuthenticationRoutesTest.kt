package net.geoshare_app

import com.android.keyattestation.verifier.VerificationResult
import io.ktor.client.call.body
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.config.mergeWith
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.geoshare_app.lib.SigningPurpose
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.base64Decode
import net.geoshare_app.lib.base64Encode
import net.geoshare_app.lib.buildSigningPayloadV1
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.formatHour
import net.geoshare_app.lib.listHours
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.lib.sign
import net.geoshare_app.testing.CertLists
import net.geoshare_app.testing.Certs
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import net.geoshare_app.testing.jsonHttpClient
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationRoutesTest {
    private val statusApiToken = "test-status-api-token"

    @Test
    fun `challenge route - always returns challenge of 32 bytes`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = jsonHttpClient.post("/v1/auth/challenge")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(32, res.body<ChallengeResponse>().challenge.base64Decode().size)

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:challenge:success:$hour:total"))
    }

    @Test
    fun `register route - when challenge is not found in cache, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Register
        val registrationChallengeBase64 = "spam".toByteArray().base64Encode()
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when challenge has been used, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        // Register 2
        val res2 = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when challenge is not valid base64, it returns 500`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                "spam".toByteArray(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = "not valid base64",
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)

        val hour = formatHour()
        assertEquals(0, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when challenge expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache(testScheduler.timeSource)
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            advanceTimeBy(3.seconds)

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid challenge", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
        }
    }

    @Test
    fun `register route - when certificate chain is invalid, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.intermediateKey // Sign with the intermediate key instead of the leaf key
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.noLeaf
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Extension parsing failure", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
    }

    @Test
    fun `register route - when signature is invalid, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.intermediateKey // Sign with the intermediate key instead of the leaf key
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `register route - when signature uses wrong purpose, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN, // Use the login purpose
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `register route - when registration fails and it is retried with the same challenge, it returns 401`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val registrationSignature =
                Certs.intermediateKey.private.sign(registrationChallengeBase64.base64Decode()) // Sign with the intermediate key instead of the leaf key
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid signature", res.body())

            // Register again with the same challenge
            val registrationSignature2 = Certs.leafKey.private.sign(registrationChallengeBase64.base64Decode()) // Sign with the correct key
            val res2 = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature2.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res2.status)
            assertEquals("Invalid challenge", res2.body())

            val hour = formatHour()
            assertEquals(2, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when certificate has been revoked, it returns 401`() = runTest {
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache(testScheduler.timeSource)
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.revoked
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Path validation failure", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }
    }

    @Test
    fun `register route - when signature is valid, it returns token`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `register route - when challenge has non-canonical encoding, it returns token`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Registration
        val nonCanonicalChallengeBase64 = registrationChallengeBase64.trimEnd('=')
        assertNotEquals(registrationChallengeBase64, nonCanonicalChallengeBase64)
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(), // Sign the canonical base64 challenge
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = nonCanonicalChallengeBase64, // Send a non-canonical base64 challenge (stripped padding)
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `register route - when legacy signature is valid, it returns token`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge
        val registrationChallenge = registrationChallengeBase64.base64Decode()

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(registrationChallenge)
        val certificateChain = CertLists.validFactoryProvisioned
        val res = jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
        assertEquals(1, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `register route - when legacy signature is valid and legacy signatures are disabled in config, it returns 401`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.legacySignatureEnabled" to "false",
                )
            )
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge
            val registrationChallenge = registrationChallengeBase64.base64Decode()

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(registrationChallenge)
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid signature", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when signature is valid and device has already been registered, it returns 409`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token.subject)
            assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

            // Registration challenge again
            val registrationChallenge2Base64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register again with a chain that classifies the device as unverified
            val registrationSignature2 = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallenge2Base64.base64Decode(),
                ).toByteArray()
            )
            val res2 = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge2Base64,
                        signature = registrationSignature2.base64Encode(),
                        certificateChain = CertLists.selfSigned.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Conflict, res2.status)
            assertEquals("Already registered", res2.body())

            // Login with the same key keeps the original device classification
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res3 = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res3.status)
            val token3 = Tokens.verify(res3.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token3.subject)
            assertEquals(Device.VERIFIED, token3.getClaim("device").asString().toDevice())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when device has already been registered and it is retried with the same challenge, it returns 401`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)

            // Registration challenge again
            val registrationChallenge2Base64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register again
            val registrationSignature2 = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallenge2Base64.base64Decode(),
                ).toByteArray()
            )
            val res2 = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge2Base64,
                        signature = registrationSignature2.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Conflict, res2.status)
            assertEquals("Already registered", res2.body())

            // Register again with the same challenge, which has been consumed
            val res3 = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge2Base64,
                        signature = registrationSignature2.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res3.status)
            assertEquals("Invalid challenge", res3.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
            assertEquals(2, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when signature is valid with self-signed key extension with unknown boot key, it returns token with unverified device`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.selfSigned
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when signature is valid with software attestation, it returns token with unverified device`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = object : CertificateVerification {
                override val cache = cache

                override suspend fun verify(@Suppress("unused", "RedundantSuppression") chain: List<X509Certificate>) =
                    VerificationResult.PathValidationFailure(
                        CertPathValidatorException(
                            "Chain terminates in a software root and no matching trust anchor was found, so the chain was not validated.",
                            Throwable(),
                        )
                    )

                override suspend fun fetchRevokedSerials() = emptySet<String>()
            }
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain =
                CertLists.validFactoryProvisioned // The chain is ignored, because we mock verification
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when signature is invalid with software attestation, it returns token with unverified device`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = object : CertificateVerification {
                override val cache = cache

                override suspend fun verify(@Suppress("unused", "RedundantSuppression") chain: List<X509Certificate>) =
                    VerificationResult.PathValidationFailure(
                        CertPathValidatorException(
                            "Chain terminates in a software root and no matching trust anchor was found, so the chain was not validated.",
                            Throwable(),
                        )
                    )

                override suspend fun fetchRevokedSerials() = emptySet<String>()
            }
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.intermediateKey // Sign with the intermediate key instead of the leaf key
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain =
                CertLists.validFactoryProvisioned // The chain is ignored, because we mock verification
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid signature", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `register route - when signature is valid with software attestation and device has already been registered, it returns 409`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val realCertificateVerification = TestCertificateVerification(cache)
            val certificateVerification = object : CertificateVerification {
                override val cache = cache

                override suspend fun verify(chain: List<X509Certificate>) =
                    when (chain) {
                        CertLists.selfSigned ->
                            VerificationResult.PathValidationFailure(
                                CertPathValidatorException(
                                    "Chain terminates in a software root and no matching trust anchor was found, so the chain was not validated.",
                                    Throwable(),
                                )
                            )

                        CertLists.validFactoryProvisioned ->
                            realCertificateVerification.verify(chain)

                        else -> throw NotImplementedError()
                    }

                override suspend fun fetchRevokedSerials() = emptySet<String>()
            }
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain =
                CertLists.selfSigned // This chain make the certificate verification mock return software attestation
            val res = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            // Registration challenge again
            val registrationChallenge2Base64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register again with a chain that classifies the device as verified
            val registrationSignature2 = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallenge2Base64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain2 =
                CertLists.validFactoryProvisioned // This chain make the certificate verification mock return hardware attestation
            val res2 = jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallenge2Base64,
                        signature = registrationSignature2.base64Encode(),
                        certificateChain = certificateChain2.map { it.encoded.base64Encode() },
                    )
                )
            }
            assertEquals(HttpStatusCode.Conflict, res2.status)
            assertEquals("Already registered", res2.body())

            // Login with the same key keeps the original device classification
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res3 = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res3.status)
            val token3 = Tokens.verify(res3.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token3.subject)
            assertEquals(Device.UNVERIFIED, token3.getClaim("device").asString().toDevice())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:register:success:$hour:total"))
            assertEquals(1, statsRepository.get("stats:auth:register:error:$hour:total"))
            assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `login route - when challenge is not found, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Login challenge
        val loginChallengeBase64 = "spam".toByteArray().base64Encode()

        // Login
        val keyPair = Certs.leafKey
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid challenge", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when challenge is not valid base64, it returns 500`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Login
        val keyPair = Certs.leafKey
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                "spam".toByteArray(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = "not valid base64",
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)

        val hour = formatHour()
        assertEquals(0, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when challenge has been used, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        // Login again
        val res2 = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when challenge expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache(testScheduler.timeSource)
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            advanceTimeBy(3.seconds)

            // Login
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid challenge", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        }
    }

    @Test
    fun `login route - when device is not found, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val keyPair = Certs.leafKey
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Unknown device", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
    }

    @Test
    fun `login route - when device is not found and it is retried with the same challenge, it returns 401`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Login
            val keyPair = Certs.leafKey
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Unknown device", res.body())

            // Login again with the same challenge, which has been consumed
            val res2 = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res2.status)
            assertEquals("Invalid challenge", res2.body())

            val hour = formatHour()
            assertEquals(2, statsRepository.get("stats:auth:login:error:$hour:total"))
        }

    @Test
    fun `login route - when device expires, it returns 401`() = runTest {
        testApplication(testScheduler) {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache(testScheduler.timeSource)
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            advanceTimeBy(7.seconds)

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Login
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Unknown device", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        }
    }

    @Test
    fun `login route - when signature is invalid, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val loginSignature =
            Certs.intermediateKey.private.sign( // Sign with the intermediate key instead of the leaf key
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `login route - when signature uses wrong purpose, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION, // Use the registration purpose
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `login route - when login fails and it is retried with the same challenge, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val loginSignature =
            Certs.intermediateKey.private.sign( // Sign with the intermediate key instead of the leaf key
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Invalid signature", res.body())

        // Login again with the same challenge
        val loginSignature2 = Certs.leafKey.private.sign( // Sign with the correct key
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res2 = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature2.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("Invalid challenge", res2.body())

        val hour = formatHour()
        assertEquals(2, statsRepository.get("stats:auth:login:error:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `login route - when signature is valid, it returns token`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Login
        val loginSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.LOGIN,
                keyPair.public.fingerprint(),
                loginChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
        assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `login route - when legacy signature is valid, it returns token`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // Registration challenge
        val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge

        // Register
        val keyPair = Certs.leafKey
        val registrationSignature = keyPair.private.sign(
            buildSigningPayloadV1(
                SigningPurpose.REGISTRATION,
                keyPair.public.fingerprint(),
                registrationChallengeBase64.base64Decode(),
            ).toByteArray()
        )
        val certificateChain = CertLists.validFactoryProvisioned
        jsonHttpClient.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    challenge = registrationChallengeBase64,
                    signature = registrationSignature.base64Encode(),
                    certificateChain = certificateChain.map { it.encoded.base64Encode() },
                )
            )
        }

        // Login challenge
        val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
            .body<ChallengeResponse>().challenge
        val loginChallenge = loginChallengeBase64.base64Decode()

        // Login
        val loginSignature = keyPair.private.sign(loginChallenge)
        val res = jsonHttpClient.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    challenge = loginChallengeBase64,
                    signature = loginSignature.base64Encode(),
                    publicKey = keyPair.public.encoded.base64Encode(),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Tokens.verify(res.body<TokenResponse>().token)
        assertEquals(keyPair.public.fingerprint(), token.subject)
        assertEquals(Device.VERIFIED, token.getClaim("device").asString().toDevice())

        val hour = formatHour()
        assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
        assertEquals(1, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
    }

    @Test
    fun `login route - when legacy signature is valid and legacy signatures are disabled in config, it returns 401`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.legacySignatureEnabled" to "false",
                )
            )
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge
            val loginChallenge = loginChallengeBase64.base64Decode()

            // Login
            val loginSignature = keyPair.private.sign(loginChallenge)
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("Invalid signature", res.body())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:login:error:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `login route - when signature is valid and device is unverified, it returns token with unverified device`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.selfSigned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Login
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val token = Tokens.verify(res.body<TokenResponse>().token)
            assertEquals(keyPair.public.fingerprint(), token.subject)
            assertEquals(Device.UNVERIFIED, token.getClaim("device").asString().toDevice())

            val hour = formatHour()
            assertEquals(1, statsRepository.get("stats:auth:login:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }

    @Test
    fun `login route - when signature is valid, it refreshes device expiration`() = runTest {
        testApplication(testScheduler) {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache(testScheduler.timeSource)
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Registration challenge
            val registrationChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Register
            val keyPair = Certs.leafKey
            val registrationSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.REGISTRATION,
                    keyPair.public.fingerprint(),
                    registrationChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val certificateChain = CertLists.validFactoryProvisioned
            jsonHttpClient.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        challenge = registrationChallengeBase64,
                        signature = registrationSignature.base64Encode(),
                        certificateChain = certificateChain.map { it.encoded.base64Encode() },
                    )
                )
            }

            // Advance time, so the device almost expires
            advanceTimeBy(5.seconds)

            // Login challenge
            val loginChallengeBase64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Login
            val loginSignature = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallengeBase64.base64Decode(),
                ).toByteArray()
            )
            val res = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallengeBase64,
                        signature = loginSignature.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res.status)

            // Advance time, so the device would expire if the expiration wasn't refreshed
            advanceTimeBy(5.seconds)

            // Login challenge
            val loginChallenge2Base64 = jsonHttpClient.post("/v1/auth/challenge")
                .body<ChallengeResponse>().challenge

            // Login
            val loginSignature2 = keyPair.private.sign(
                buildSigningPayloadV1(
                    SigningPurpose.LOGIN,
                    keyPair.public.fingerprint(),
                    loginChallenge2Base64.base64Decode(),
                ).toByteArray()
            )
            val res2 = jsonHttpClient.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    LoginRequest(
                        challenge = loginChallenge2Base64,
                        signature = loginSignature2.base64Encode(),
                        publicKey = keyPair.public.encoded.base64Encode(),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, res2.status)

            val hour = formatHour()
            assertEquals(2, statsRepository.get("stats:auth:login:success:$hour:total"))
            assertEquals(0, statsRepository.get("stats:auth:legacy-signature:success:$hour:total"))
        }
    }

    @Test
    fun `status challenge success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/challenge/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status legacy signature success route - when the number exceeds threshold, it returns failure`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // When the number is zero, it returns success
            val resSuccess = client.head("/v1/status/auth/legacy-signature/success/7days") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.OK, resSuccess.status)

            // When the number is positive, it returns failure
            val localDateTime = LocalDateTime.now()
            localDateTime.listHours(0L downTo -4L).forEach {
                statsRepository.increase("stats:auth:legacy-signature:success:${formatHour(it)}:total")
            }
            val resFailure = client.head("/v1/status/auth/legacy-signature/success/7days") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(StatusFailed, resFailure.status)
        }

    @Test
    fun `status legacy signature success route - when legacy signature use is older than 7 days, it returns success`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
            val cache = FakeCache()
            val certificateVerification = TestCertificateVerification(cache)
            val statsRepository = StatsRepository(cache)
            val authentication = Authentication(
                authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticationRoutes(authentication, statsRepository)
            }

            // Legacy signature use outside the 7-day window is ignored
            val localDateTime = LocalDateTime.now()
            localDateTime.listHours(-168L downTo -172L).forEach {
                statsRepository.increase("stats:auth:legacy-signature:success:${formatHour(it)}:total")
            }
            val res = client.head("/v1/status/auth/legacy-signature/success/7days") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `status legacy signature success route - when called with incorrect token, it returns 401`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/legacy-signature/success/7days") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status login success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/login/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status login error route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/login/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status register success route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/register/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status register error route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/register/error/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status unauthorized route - when the number exceeds threshold, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
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
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val statsRepository = StatsRepository(cache)
        val authentication = Authentication(
            authenticationConfig = config.property("auth").getAs<AuthenticationConfig>(),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            authenticationRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/auth/unauthorized/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = formatHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }
}
