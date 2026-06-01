package net.geoshare_app

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

class GoogleMapsTest {
    @Test
    fun `geocode place id route when no token is passed returns 401`() = testApplication {
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
    fun `geocode place id route when expired token is passed returns 401`() = testApplication {
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
    fun `geocode place id route when google api returns 404 returns 404`() = testApplication {
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
    fun `geocode place id route when google api returns 200 returns 200`() = testApplication {
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
        assertEquals("""{"location":{"latitude":50.12345,"longitude":-11.12345}}""", res.bodyAsText())
    }

    @Test
    fun `geocode place id route when google api returns invalid response returns 500`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.INVALID_RESPONSE}") {
            headers["Authorization"] = "Bearer ${Tokens.valid}"
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("500: Google Maps request failed.", res.bodyAsText())
    }

    @Test
    fun `geocode place id route when called fast header returns 429`() = testApplication {
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            googleMapsModule(googleMapsClient = FakeGoogleMapsClient())
        }

        for (ip in listOf(null, "10.10.10.1", "10.10.10.2")) {
            repeat(5) {
                val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
                    headers["Authorization"] = "Bearer ${Tokens.valid}"
                    if (ip != null) {
                        headers["X-Forwarded-For"] = ip
                    }
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            val res = client.get("/v1/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT}") {
                headers["Authorization"] = "Bearer ${Tokens.valid}"
                if (ip != null) {
                    headers["X-Forwarded-For"] = ip
                }
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)
        }
    }
}
