@file:OptIn(ExperimentalKtorApi::class)

package net.geoshare_app

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticateWith
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.formatHour
import net.geoshare_app.testing.Certs
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitModuleTest {
    @Test
    fun `rate limited route per ip - when called too fast from an unknown ip, it returns 429`() = testApplication {
        val config = ApplicationConfig("application-test.conf")
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            this.config = config
        }
        install(ContentNegotiation) { json() }
        install(Resources)
        application {
            rateLimitModule()
            statusPagesModule(statsRepository)
        }
        routing {
            rateLimit {
                get("/test") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // The first few requests pass
        repeat(10) {
            val res = client.get("/test") {
                // X-Real-Ip header is not set
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/test") {
            headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
            // X-Real-Ip header is not set
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)

        val hour = formatHour()
        val endpoint = "test"
        val ip = "unknown"
        val subject = "unknown"
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
        assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
    }

    @Test
    fun `rate limited route per ip - when called too fast from ipv4 addresses with the same prefix, it returns 429`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            install(Resources)
            application {
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                rateLimit {
                    get("/test") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            // The first few requests pass
            repeat(10) {
                val res = client.get("/test") {
                    headers["X-Real-Ip"] = "203.0.113.1"
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request is rate-limited
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                headers["X-Real-Ip"] = "203.0.113.2" // Different IPv4 address with the same /24 prefix
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "203.0.113.1"
            val ip2 = "203.0.113.2"
            val subject = "unknown"
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip2))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per ip - when called too fast from ipv6 addresses with the same prefix, it returns 429`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            install(Resources)
            application {
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                rateLimit {
                    get("/test") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            // The first few requests pass
            repeat(10) {
                val res = client.get("/test") {
                    headers["X-Real-Ip"] = "2001:db8:dead:beef::1"
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request is rate-limited
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                headers["X-Real-Ip"] = "2001:db8:dead:beef::2" // Different IPv6 address with the same /64 prefix
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "2001:db8:dead:beef::1"
            val ip2 = "2001:db8:dead:beef::2"
            val subject = "unknown"
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip2))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per ip - when called too fast from ipv4 addresses with different prefix, it returns 200`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            install(Resources)
            application {
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                rateLimit {
                    get("/test") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            // The first few requests pass
            repeat(10) {
                val res = client.get("/test") {
                    headers["X-Real-Ip"] = "192.0.2.1"
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request passes too
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                headers["X-Real-Ip"] = "192.0.3.1" // Different IPv4 address with the same /16 prefix
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "192.0.2.1"
            val ip2 = "192.0.3.1"
            val subject = "unknown"
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip2))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(0, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per ip - when called too fast from ipv6 addresses with different prefix, it returns 200`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            install(Resources)
            application {
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                rateLimit {
                    get("/test") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            // The first few requests pass
            repeat(10) {
                val res = client.get("/test") {
                    headers["X-Real-Ip"] = "2001:db8:dead:beef::1"
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request passes too
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                headers["X-Real-Ip"] = "2001:db8:dead::1" // Different IPv6 address with the same /48 prefix
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "2001:db8:dead:beef::1"
            val ip2 = "2001:db8:dead::1"
            val subject = "unknown"
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip2))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(0, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per user - when called too fast with the same token of an unverified device, it returns 429`() =
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
            install(Resources)
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticateWith(authentication.userScheme) {
                    rateLimit(RateLimitName("per-user")) {
                        get("/test") {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
            }

            // The first few requests pass
            repeat(3) {
                val res = client.get("/test") {
                    headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validUnverifiedDevice}"
                    headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request is rate-limited
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validUnverifiedDevice}" // Same token
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "203.0.113.1"
            val subject = Certs.leafKey.public.fingerprint()
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per user - when called too fast with the same token of a verified device, it returns 429`() =
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
            install(Resources)
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticateWith(authentication.userScheme) {
                    rateLimit(RateLimitName("per-user")) {
                        get("/test") {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
            }

            // The first few requests pass
            repeat(6) {
                val res = client.get("/test") {
                    headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                    headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request is rate-limited
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}" // Same token
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "203.0.113.1"
            val subject = Certs.leafKey.public.fingerprint()
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per user - when called too fast without device and from ipv4 addresses with the same prefix, it returns 429`() =
        testApplication {
            val config = ApplicationConfig("application-test.conf")
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            environment {
                this.config = config
            }
            install(ContentNegotiation) { json() }
            install(Resources)
            application {
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                rateLimit(RateLimitName("per-user")) {
                    get("/test") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            // The first few requests pass
            repeat(3) {
                val res = client.get("/test") {
                    headers["X-Real-Ip"] = "203.0.113.1"
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request is rate-limited
            val res = client.get("/test") {
                headers["X-Real-Ip"] = "203.0.113.2" // Different IPv4 address with the same /24 prefix
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "203.0.113.1"
            val ip2 = "203.0.113.2"
            val subject = "unknown"
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip2))
            assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
        }

    @Test
    fun `rate limited route per user - when called too fast with different tokens, it returns 200`() =
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
            install(Resources)
            application {
                authenticationModule(certificateVerification)
                rateLimitModule()
                statusPagesModule(statsRepository)
            }
            routing {
                authenticateWith(authentication.userScheme) {
                    rateLimit(RateLimitName("per-user")) {
                        get("/test") {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
            }

            // The first few requests pass
            repeat(6) {
                val res = client.get("/test") {
                    headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDevice}"
                    headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            // The next request passes too
            val res = client.get("/test") {
                headers[HttpHeaders.Authorization] = "Bearer ${Tokens.validVerifiedDeviceTwo}" // Different token
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)

            val hour = formatHour()
            val endpoint = "test"
            val ip = "203.0.113.1"
            val subject = Certs.leafKey.public.fingerprint()
            val subject2 = "test-public-key-fingerprint-two"
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
            assertEquals(0, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject2))
            assertEquals(0, statsRepository.get("stats:rate-limit:$hour:total"))
        }
}
