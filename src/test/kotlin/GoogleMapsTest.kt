package net.geoshare_app

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.FakeGoogleMapsClient
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleMapsTest {
    @Test
    fun `geocode address route - when no token is passed, it returns 401`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode address route - when expired token is passed, it returns 401`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.expired}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode address route - when google api returns 200, it returns 200`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            // language=Json
            """
                {
                    "results": [
                        {
                            "location": {"latitude": 50.123456, "longitude": -11.123456}
                        },
                        {
                            "location": {"latitude": 9.0, "longitude": -120.0}
                        }
                    ]
                }
            """.trimIndent().replace("\n", "").replace(" ", ""),
            res.bodyAsText(),
        )
    }

    @Test
    fun `geocode address route - when google api returns 401, it returns 500`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.UNAUTHORIZED}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
    }

    @Test
    fun `geocode address route - when google api returns 404, it returns 404`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.NOT_FOUND}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `geocode address route - when google api throws exception, it returns 500`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/address/${FakeGoogleMapsClient.EXCEPTION}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
    }

    @Test
    fun `geocode address route - when called too fast, it returns 429`() = testApplication {
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
            headers["X-Real-Ip"] = "203.0.113.1"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test
    fun `geocode place route - when no token is passed, returns 401`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode place route - when expired token is passed, it returns 401`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.expired}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode place route - when google api returns 200, it returns 200`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            // language=Json
            """
                {
                    "location": {"latitude": 50.123456, "longitude": -11.123456}
                }
            """.trimIndent().replace("\n", "").replace(" ", ""),
            res.bodyAsText(),
        )
    }

    @Test
    fun `geocode place route - when google api returns 401, it returns 500`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.UNAUTHORIZED}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
    }

    @Test
    fun `geocode place route - when google api returns 404, it returns 404`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.NOT_FOUND}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `geocode place route - when google api throws exception, it returns 500`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.EXCEPTION}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
    }

    @Test
    fun `geocode place route - when called too fast, it returns 429`() = testApplication {
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
            val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                headers["X-Real-Ip"] = "203.0.113.1"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            headers["X-Real-Ip"] = "203.0.113.1"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
    }

    @Test
    fun `status route - when called with correct token and google api returns expected location with tiny delta, it returns 200`() = testApplication {
        val statusApiKey = "test-status-api-key"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiKeyHash" to statusApiKey.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(
                googleMapsClient = FakeGoogleMapsClient(
                    statusLocation = GoogleMapsLocation(47.5951518, -122.33163940000001),
                ),
            )
        }

        val res = client.get("/v1/google-maps/status") {
            headers["X-Api-Key"] = statusApiKey
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `status route - when called with correct token and google api returns unexpected location, it returns 500`() = testApplication {
        val statusApiKey = "test-status-api-key"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiKeyHash" to statusApiKey.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(
                googleMapsClient = FakeGoogleMapsClient(
                    statusLocation =GoogleMapsLocation(3.14, -120.0),
                ),
            )
        }

        val res = client.get("/v1/google-maps/status") {
            headers["X-Api-Key"] = statusApiKey
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("Unexpected location", res.bodyAsText())
    }

    @Test
    fun `status route - when called with correct token and google api returns no results, it returns 500`() = testApplication {
        val statusApiKey = "test-status-api-key"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiKeyHash" to statusApiKey.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/status") {
            headers["X-Api-Key"] = statusApiKey
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("No results", res.bodyAsText())
    }

    @Test
    fun `status route - when called with incorrect token, it returns 401`() = testApplication {
        val statusApiKey = "test-status-api-key"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiKeyHash" to statusApiKey.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/status") {
            headers["X-Api-Key"] = "spam"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
