package net.geoshare_app

import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class StatusTest {
    @Test
    fun `status route - when called with correct token and cache ping succeeds, it returns 200`() = testApplication {
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            statusModule(cache)
        }

        val res = client.head("/v1/status") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `status route - when called with correct token and cache ping fails, it returns 500`() = testApplication {
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            @Suppress("RedundantSuppression")
            val cache = object : Cache {
                @Suppress("EmptyMethod", "unused")
                override suspend fun get(key: String) = ""

                @Suppress("EmptyMethod", "unused")
                override suspend fun set(key: String, value: String) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun set(key: String, value: String, expire: Duration) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun delete(key: String) {}

                @Suppress("EmptyMethod", "unused")
                override suspend fun expire(key: String, expire: Duration) {}

                override suspend fun ping() = false

                @Suppress("EmptyMethod")
                override fun close() {}
            }
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            statusModule(cache)
        }

        val res = client.head("/v1/status") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("Failed", res.bodyAsText())
    }

    @Test
    fun `status route - when called with incorrect token, it returns 401`() = testApplication {
        val statusApiToken = "test-status-api-token"
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.statusApiTokenHash" to statusApiToken.toByteArray().sha256Hex(),
                )
            )
        }
        application {
            val cache = FakeCache()
            rootModule()
            authenticationModule(cache, TestCertificateVerification(cache))
            statusModule(cache)
        }

        val res = client.head("/v1/status") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
