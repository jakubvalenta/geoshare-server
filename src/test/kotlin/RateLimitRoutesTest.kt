package net.geoshare_app

import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.config.mergeWith
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.formatHour
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitRoutesTest {
    private val statusApiToken = "test-status-api-token"

    @Test
    fun `status rate limit route - when the number exceeds threshold, it returns failure`() = testApplication {
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
            rateLimitRoutes(authentication, statsRepository)
        }

        // When the number is low, it returns success
        val hour = formatHour()
        repeat(5) {
            statsRepository.increase("stats:rate-limit:$hour:total")
        }
        val resSuccess = client.head("/v1/status/rate-limit/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:rate-limit:$hour:total")
        val resFailure = client.head("/v1/status/rate-limit/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status rate limit route - when called with incorrect token, it returns 401`() = testApplication {
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
            rateLimitRoutes(authentication, statsRepository)
        }

        val res = client.head("/v1/status/rate-limit/hour") {
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
