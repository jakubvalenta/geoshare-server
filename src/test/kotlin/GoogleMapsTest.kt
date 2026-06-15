package net.geoshare_app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import net.geoshare_app.lib.CallDetails
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.testing.Certs
import net.geoshare_app.testing.FakeCache
import net.geoshare_app.testing.TestCertificateVerification
import net.geoshare_app.testing.Tokens
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleMapsTest {
    private val apiKey = "test-api-key"
    private val placeId = "foo"
    private val query = "Cherbourg, France"
    private val engine = MockEngine { request ->
        if (request.headers["X-Goog-Api-Key"] != apiKey) {
            return@MockEngine respondError(HttpStatusCode.Unauthorized)
        }
        when {
            request.url.toString().startsWith("https://geocode.googleapis.com/v4/geocode/address/") ->
                assertEquals("results.location", request.headers["X-Goog-FieldMask"])

            request.url.toString().startsWith("https://geocode.googleapis.com/v4/geocode/places/") ->
                assertEquals("location", request.headers["X-Goog-FieldMask"])

            else ->
                throw NotImplementedError()
        }
        when (request.url.toString()) {
            "https://geocode.googleapis.com/v4/geocode/address/Cherbourg,%20France" -> respond(
                // language=Json
                """
                    {
                        "results": [
                            {"place": "//places.googleapis.com/places/foo", "location": {"latitude": 50.123456, "longitude": -11.123456}},
                            {"place": "//places.googleapis.com/places/bar", "location": {"latitude": 9, "longitude": -120}}
                        ]
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/address/empty-results" -> respond(
                // language=Json
                """{"results": []}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/address/empty-object" -> respond(
                // language=Json
                """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/address/invalid" -> respond(
                // language=Json
                """{"results": "invalid"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/address/exception" -> throw SocketTimeoutException()

            "https://geocode.googleapis.com/v4/geocode/address/bad-request" -> respondError(HttpStatusCode.BadRequest)

            "https://geocode.googleapis.com/v4/geocode/address/not-found" -> respondError(HttpStatusCode.NotFound)

            "https://geocode.googleapis.com/v4/geocode/address/too-many-requests" -> respondError(HttpStatusCode.TooManyRequests)

            "https://geocode.googleapis.com/v4/geocode/address/unauthorized" -> respondError(HttpStatusCode.Unauthorized)

            "https://geocode.googleapis.com/v4/geocode/places/foo" -> respond(
                // language=Json
                """
                    {
                        "place": "//places.googleapis.com/places/foo",
                        "location": {"latitude": 50.123456, "longitude": -11.123456}
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/places/empty-object" -> respond(
                // language=Json
                """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/places/invalid" -> respond(
                // language=Json
                """{"location": "invalid"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "https://geocode.googleapis.com/v4/geocode/places/exception" -> throw SocketTimeoutException()

            "https://geocode.googleapis.com/v4/geocode/places/bad-request" -> respondError(HttpStatusCode.BadRequest)

            "https://geocode.googleapis.com/v4/geocode/places/not-found" -> respondError(HttpStatusCode.NotFound)

            "https://geocode.googleapis.com/v4/geocode/places/too-many-requests" -> respondError(HttpStatusCode.TooManyRequests)

            "https://geocode.googleapis.com/v4/geocode/places/unauthorized" -> respondError(HttpStatusCode.Unauthorized)

            else -> throw NotImplementedError()
        }
    }

    @Test
    fun `geocode address route - when no token is passed, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for (device in listOf("unverified", "verified")) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query")
            assertEquals(HttpStatusCode.Unauthorized, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `geocode address route - when expired token is passed, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.expired, "verified" to Tokens.expired)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `geocode address route - when google api key is incorrect, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.apiKey" to "spam",
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "401"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when upstream returns results, it returns 200`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository = statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(
                // language=Json
                """
                {
                    "results": [
                        {"location": {"latitude": 50.123456, "longitude": -11.123456}},
                        {"location": {"latitude": 9.0, "longitude": -120.0}}
                    ]
                }
            """.trimIndent().replace("\n", "").replace(" ", ""),
                res.bodyAsText(),
            )

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:success:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:google-maps:success:$hour:total"))
    }

    @Test
    fun `geocode address route - when engine returns empty results, it returns 200`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/empty-results") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(
                // language=Json
                """{"results":[]}""",
                res.bodyAsText(),
            )

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:success:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:google-maps:success:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api returns empty object, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/empty-object") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "json-convert-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api returns invalid response, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/invalid") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "json-convert-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api throws bad request, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/bad-request") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "400"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api throws not found, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/not-found") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "404"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api throws too many requests, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/too-many-requests") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "429"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api throws unauthorized, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/unauthorized") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "401"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address route - when google maps api throws exception, it return 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/address/exception") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-address"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "unknown"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode address unverified route - when called too fast, it returns 429`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "unverified"
        val token = Tokens.validUnverifiedDevice
        val hour = CallDetails.formatCurrentHour()
        val ip = "203.0.113.1"
        val subject = Certs.leafKey.public.fingerprint()
        // The first few requests pass
        repeat(1) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
            headers[HttpHeaders.Authorization] = "Bearer $token"
            headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)

        val endpoint = "google-maps-$device-address"
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
        assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
    }

    @Test
    fun `geocode address verified route - when called too fast, it returns 429`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "verified"
        val token = Tokens.valid
        val hour = CallDetails.formatCurrentHour()
        val ip = "203.0.113.1"
        val subject = Certs.leafKey.public.fingerprint()
        // The first few requests pass
        repeat(5) {
            val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/$device/geocode/address/$query") {
            headers[HttpHeaders.Authorization] = "Bearer $token"
            headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)

        val endpoint = "google-maps-$device-address"
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
        assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
    }

    @Test
    fun `geocode places unverified route - when no token is passed, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "unverified"
        val hour = CallDetails.formatCurrentHour()
        val res = client.get("/v1/google-maps/$device/geocode/places/$placeId")
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val endpoint = "google-maps-$device-places"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `geocode places verified route - when no token is passed, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "unverified"
        val hour = CallDetails.formatCurrentHour()
        val res = client.get("/v1/google-maps/$device/geocode/places/$placeId")
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val endpoint = "google-maps-$device-places"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `geocode places route - when expired token is passed, it returns 401`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "verified"
        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.expired, "verified" to Tokens.expired)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/$placeId") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `geocode places route - when google api key is incorrect, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf").mergeWith(
                MapApplicationConfig(
                    "googleMaps.apiKey" to "spam",
                )
            )
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/$placeId") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "401"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when upstream returns results, it returns 200`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/$placeId") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
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

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:success:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.get("stats:google-maps:success:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api returns empty object, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/empty-object") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "json-convert-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api returns invalid response, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/invalid") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "json-convert-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api throws bad request, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/bad-request") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "400"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api throws not found, it returns 404`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/not-found") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.NotFound, res.status)

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "404"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api throws too many requests, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/too-many-requests") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "429"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api throws unauthorized, it returns 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/unauthorized") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-code", "401"))
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "client-request-exception"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places route - when google maps api throws exception, it return 500`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val hour = CallDetails.formatCurrentHour()
        for ((device, token) in listOf("unverified" to Tokens.validUnverifiedDevice, "verified" to Tokens.valid)) {
            val res = client.get("/v1/google-maps/$device/geocode/places/exception") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())

            val endpoint = "google-maps-$device-places"
            assertEquals(1, statsRepository.hashGet("stats:google-maps:exception:$hour:by-endpoint", endpoint))
        }
        assertEquals(2, statsRepository.hashGet("stats:google-maps:exception:$hour:by-type", "unknown"))
        assertEquals(2, statsRepository.get("stats:google-maps:exception:$hour:total"))
    }

    @Test
    fun `geocode places unverified route - when called too fast, it returns 429`() = testApplication {
        val cache = FakeCache()
        val statsRepository = StatsRepository(cache)
        environment {
            config = ApplicationConfig("application-test.conf")
        }
        application {
            rootModule(statsRepository)
            authenticationModule(cache, TestCertificateVerification(cache), statsRepository)
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val device = "unverified"
        val token = Tokens.validUnverifiedDevice
        val hour = CallDetails.formatCurrentHour()
        val ip = "203.0.113.1"
        val subject = Certs.leafKey.public.fingerprint()
        // The first few requests pass
        repeat(1) {
            val res = client.get("/v1/google-maps/$device/geocode/places/$placeId") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
                headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
        // The next request is rate-limited
        val res = client.get("/v1/google-maps/$device/geocode/places/$placeId") {
            headers[HttpHeaders.Authorization] = "Bearer $token"
            headers["X-Real-Ip"] = "203.0.113.1" // IP address should not affect rate limiting
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)

        val endpoint = "google-maps-$device-places"
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-ip", ip))
        assertEquals(1, statsRepository.hashGet("stats:rate-limit:$hour:by-subject", subject))
        assertEquals(1, statsRepository.get("stats:rate-limit:$hour:total"))
    }

    @Test
    fun `status connection route - when upstream returns expected location with tiny delta, it returns 200`() =
        testApplication {
            val cache = FakeCache()
            val statsRepository = StatsRepository(cache)
            val statusApiToken = "test-status-"
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
                googleMapsModule(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://geocode.googleapis.com/v4/geocode/address/Lumen%20Field" -> respond(
                                // language=Json
                                """
                                        {
                                            "results": [
                                                {"location": {"latitude": 47.5951518, "longitude": -122.33163940000001}}
                                            ]
                                        }
                                    """.trimIndent(),
                                headers = headersOf(
                                    HttpHeaders.ContentType, ContentType.Application.Json.toString()
                                ),
                            )

                            else -> throw NotImplementedError()
                        }
                    },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `status connection route - when upstream returns unexpected location, it returns failure`() =
        testApplication {
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
                googleMapsModule(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://geocode.googleapis.com/v4/geocode/address/Lumen%20Field" -> respond(
                                // language=Json
                                """
                                    {
                                        "results": [
                                            {"location": {"latitude": 3.14, "longitude": -120.0}}
                                        ]
                                    }
                                """.trimIndent(),
                                headers = headersOf(
                                    HttpHeaders.ContentType, ContentType.Application.Json.toString()
                                ),
                            )

                            else -> throw NotImplementedError()
                        }
                    },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(StatusFailed, res.status)
            assertEquals("Unexpected location", res.bodyAsText())
        }

    @Test
    fun `status connection route - when upstream returns no results, it returns 500`() =
        testApplication {
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
                googleMapsModule(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://geocode.googleapis.com/v4/geocode/address/Lumen%20Field" -> respond(
                                // language=Json
                                """
                                    {
                                        "results": []
                                    }
                                """.trimIndent(),
                                headers = headersOf(
                                    HttpHeaders.ContentType, ContentType.Application.Json.toString()
                                ),
                            )

                            else -> throw NotImplementedError()
                        }
                    },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(StatusFailed, res.status)
            assertEquals("Unexpected location", res.bodyAsText())
        }

    @Test
    fun `status connection route - when upstream throws unauthorized, it returns 500`() =
        testApplication {
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
                googleMapsModule(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://geocode.googleapis.com/v4/geocode/address/Lumen%20Field" ->
                                respondError(HttpStatusCode.Unauthorized)

                            else -> throw NotImplementedError()
                        }
                    },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())
        }

    @Test
    fun `status connection route - when upstream throws not found, it returns 404`() =
        testApplication {
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
                googleMapsModule(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://geocode.googleapis.com/v4/geocode/address/Lumen%20Field" ->
                                respondError(HttpStatusCode.NotFound)

                            else -> throw NotImplementedError()
                        }
                    },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `status connection route - when upstream throws exception, it returns 500`() =
        testApplication {
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
                googleMapsModule(
                    MockEngine { throw SocketTimeoutException() },
                    statsRepository,
                )
            }

            val res = client.head("/v1/status/google-maps/connection") {
                headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
            }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertEquals("Upstream request failed", res.bodyAsText())
        }

    @Test
    fun `status connection route - when called with incorrect token, it returns 401`() = testApplication {
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
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val res = client.head("/v1/status/google-maps/connection") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status success route - when the number exceeds threshold, it returns failure`() = testApplication {
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
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(100) {
            statsRepository.increase("stats:google-maps:success:$hour:total")
        }
        val resSuccess = client.head("/v1/status/google-maps/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:google-maps:success:$hour:total")
        val resFailure = client.head("/v1/status/google-maps/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status success route - when called with incorrect token, it returns 401`() = testApplication {
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
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val res = client.head("/v1/status/google-maps/success/hour") {
            headers[HttpHeaders.Authorization] = "Bearer spam"
            headers["X-Real-Ip"] = "203.0.113.1"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)

        val hour = CallDetails.formatCurrentHour()
        val endpoint = "status"
        assertEquals(1, statsRepository.hashGet("stats:auth:unauthorized:$hour:by-endpoint", endpoint))
        assertEquals(1, statsRepository.get("stats:auth:unauthorized:$hour:total"))
    }

    @Test
    fun `status exception route - when the number exceeds threshold, it returns failure`() = testApplication {
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
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        // When the number is low, it returns success
        val hour = CallDetails.formatCurrentHour()
        repeat(5) {
            statsRepository.increase("stats:google-maps:exception:$hour:total")
        }
        val resSuccess = client.head("/v1/status/google-maps/exception/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(HttpStatusCode.OK, resSuccess.status)

        // When the number exceeds threshold, it returns failure
        statsRepository.increase("stats:google-maps:exception:$hour:total")
        val resFailure = client.head("/v1/status/google-maps/exception/hour") {
            headers[HttpHeaders.Authorization] = "Bearer $statusApiToken"
        }
        assertEquals(StatusFailed, resFailure.status)
    }

    @Test
    fun `status exception route - when called with incorrect token, it returns 401`() = testApplication {
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
            googleMapsModule(this@GoogleMapsTest.engine, statsRepository)
        }

        val res = client.head("/v1/status/google-maps/exception/hour") {
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
