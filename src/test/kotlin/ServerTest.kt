package net.geoshare_app

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

class ServerTest {

    @Test
    fun `root route returns 404`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/").status,
        )
    }

    @Test
    fun `geocode place id route when unknown api key is passed returns 401`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v4/geocode/places/id").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v4/geocode/places/id") {
                headers["X-Api-Key"] = "spam"
            }.status,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `geocode place id route when correct api key is passed returns 200`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v4/geocode/places/id") {
                headers["X-Api-Key"] = "test"
            }.status,
        )
    }
}
