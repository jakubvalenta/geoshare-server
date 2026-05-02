package net.geoshare_app

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest : BaseTest {

    @Test
    fun `root route returns 404`() = testApplication {
        configure("application-test.conf")
        val res = client.get("/")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
