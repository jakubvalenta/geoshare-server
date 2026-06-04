package net.geoshare_app

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.FakeGoogleMapsClient
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `root route -- returns 404`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `rate limited route - when called too fast from an unknown ip, it returns 429`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                // X-Real-Ip header is not set
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            // X-Real-Ip header is not set
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test
    fun `rate limited route - when called too fast from ipv4 addresses with the same prefix, it returns 429`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                headers["X-Real-Ip"] = "203.0.113.1"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            headers["X-Real-Ip"] = "203.0.113.2" // Different IPv4 address with the same /24 prefix
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test
    fun `rate limited route - when called too fast from ipv6 addresses with the same prefix, it returns 429`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                headers["X-Real-Ip"] = "2001:db8:dead:beef::1"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            headers["X-Real-Ip"] = "2001:db8:dead:beef::2" // Different IPv6 address with the same /64 prefix
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test
    fun `rate limited route - when called too fast from ipv4 addresses with different prefix, it returns 200`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                headers["X-Real-Ip"] = "192.0.2.1"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request passes too
        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            headers["X-Real-Ip"] = "192.0.3.1" // Different IPv4 address with the same /16 prefix
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `rate limited route - when called too fast from ipv6 addresses with different prefix, it returns 200`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                headers["X-Real-Ip"] = "2001:db8:dead:beef::1"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request passes too
        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            headers["X-Real-Ip"] = "2001:db8:dead::1" // Different IPv6 address with the same /48 prefix
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
