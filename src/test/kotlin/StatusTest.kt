package net.geoshare_app

import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.geoshare_app.lib.CallDetails
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class StatusTest {
    @Test
    fun `status cache route - when cache ping succeeds, it returns 200`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `status cache route - when cache ping fails, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            @Suppress("RedundantSuppression")
            val cache = object : Cache {
                @Suppress("EmptyMethod", "unused")
                override suspend fun get(key: String) = ""

                @Suppress("EmptyMethod", "unused")
                override suspend fun hashGet(key: String, field: String) = null

                @Suppress("EmptyMethod", "unused")
                override suspend fun set(key: String, value: String) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun set(key: String, value: String, expire: Duration) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun increase(key: String, expire: Duration) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun hashIncrease(key: String, field: String, expire: Duration) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun delete(key: String) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun expire(key: String, expire: Duration) {}

                override suspend fun ping() = false

                @Suppress("EmptyMethod")
                override fun close() {}
            }
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, res.status)
    }

    @Test
    fun `status cache route - when called with incorrect token, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `status rate limit route - when the number exceeds threshold, it returns failure`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
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
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            statusModule(cache, statsRepository)
        }

        val res = client.head("/v1/status/rate-limit/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }
}
