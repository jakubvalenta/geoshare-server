package net.geoshare_app

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleMapsTest : BaseTest {

    @Test
    fun `geocode place id route when no api key is passed returns 401`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT_PLACE_ID}")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode place id route when unknown api key is passed returns 401`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT_PLACE_ID}") {
            headers["X-Api-Key"] = BaseTest.UNKNOWN_API_KEY
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `geocode place id route when google api returns 404 returns 404`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.NOT_FOUND_PLACE_ID}") {
            headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `geocode place id route when google api returns 404 but place id is cached returns 200`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.NOT_FOUND_CACHED_PLACE_ID}") {
            headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"latitude":22.22,"longitude":111.11}""", res.bodyAsText())
    }

    @Test
    fun `geocode place id route when google api returns 200 returns 200`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT_PLACE_ID}") {
            headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"latitude":50.12345,"longitude":-11.12345}""", res.bodyAsText())
    }

    @Test
    fun `geocode place id route when google api returns invalid response returns 500`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.INVALID_RESPONSE_PLACE_ID}") {
            headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("500: Google Maps request failed.", res.bodyAsText())
    }

    @Test
    fun `geocode place id route when called fast header returns 429`() = testApplication {
        configure("application-test.conf")
        for (ip in listOf(null, "10.10.10.1", "10.10.10.2")) {
            repeat(5) {
                val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT_PLACE_ID}") {
                    headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
                    if (ip != null) {
                        headers["X-Forwarded-For"] = ip
                    }
                    accept(ContentType.Application.Json)
                }
                assertEquals(HttpStatusCode.OK, res.status)
            }
            val res = client.get("/google-maps/geocode/places/${FakeGoogleMapsClient.CORRECT_PLACE_ID}") {
                headers["X-Api-Key"] = BaseTest.CORRECT_API_KEY
                if (ip != null) {
                    headers["X-Forwarded-For"] = ip
                }
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)
        }
    }
}
