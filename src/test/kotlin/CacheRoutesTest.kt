package net.geoshare_app

import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class CacheRoutesTest {
    private val statusApiToken = "test-status-api-token"

    @Test
    fun `status cache route - when cache ping succeeds, it returns 200`() = testApplication {
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
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
        }
        routing {
            cacheRoutes(authentication, cache)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `status cache route - when cache ping fails, it returns failure`() = testApplication {
        val config = ApplicationConfig("application-test.conf").mergeWith(
            MapApplicationConfig(
                "auth.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
            )
        )
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

            @Suppress("SameReturnValue", "unused")
            override suspend fun setIfNotExists(key: String, value: String, expire: Duration) = false

            @Suppress("EmptyMethod", "unused")
            override suspend fun increase(key: String, expire: Duration) {}

            @Suppress("EmptyMethod", "unused")
            override suspend fun hashIncrease(key: String, field: String, expire: Duration) {}

            @Suppress("EmptyMethod", "unused")
            override suspend fun delete(key: String) = false

            @Suppress("EmptyMethod", "unused")
            override suspend fun expire(key: String, expire: Duration) {}

            override suspend fun ping() = false

            @Suppress("EmptyMethod")
            override fun close() {}
        }
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
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
        }
        routing {
            cacheRoutes(authentication, cache)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, res.status)
    }

    @Test
    fun `status cache route - when called with incorrect token, it returns 401`() = testApplication {
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
        application {
            authenticationModule(certificateVerification)
            rateLimitModule()
        }
        routing {
            cacheRoutes(authentication, cache)
        }

        val res = client.head("/v1/status/cache/connection") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
