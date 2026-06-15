package net.geoshare_app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `root route -- returns 404`() = testApplication {
        val cache = FakeCache()
        val certificateVerification = TestCertificateVerification(cache)
        val engine = MockEngine { throw NotImplementedError() }
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(cache, certificateVerification, engine, statsRepository)
        }

        val res = client.get("/")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
