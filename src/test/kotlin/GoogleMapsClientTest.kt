package net.geoshare_app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.test.assertEquals

class GoogleMapsClientTest {
    private val apiKey = "test_api_key"
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

            else -> throw NotImplementedError()
        }
    }
    private val client = DefaultGoogleMapsClient(engine)

    @Test(expected = GoogleMapsUnauthorizedException::class)
    fun `geocodeAddress - when api key is incorrect, it throws unauthorized exception`() = runTest {
        client.geocodeAddress(apiKey = "spam", query = query)
    }

    @Test
    fun `geocodeAddress - when engine returns valid response, it returns results`() = runTest {
        assertEquals(
            GoogleMapsResults(
                listOf(
                    GoogleMapsResult(GoogleMapsLocation(50.123456, -11.123456)),
                    GoogleMapsResult(GoogleMapsLocation(9.0, -120.0)),
                )
            ),
            client.geocodeAddress(apiKey = apiKey, query = query)
        )
    }

    @Test
    fun `geocodeAddress - when engine returns empty results, it returns empty results`() = runTest {
        assertEquals(
            GoogleMapsResults(emptyList()),
            client.geocodeAddress(apiKey = apiKey, query = "empty-results"),
        )
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodeAddress - when engine returns empty object, it throws not found exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "empty-object")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodeAddress - when engine returns invalid response, it throws not found exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "invalid")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodeAddress - when engine throws bad request, it throws not found exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "bad-request")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodeAddress - when engine throws not found, it throws not found exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "not-found")
    }

    @Test(expected = GoogleMapsUnknownException::class)
    fun `geocodeAddress - when engine throws too many requests, it throws unknown exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "too-many-requests")
    }

    @Test(expected = GoogleMapsUnknownException::class)
    fun `geocodeAddress - when engine throws exception, it throws unknown exception`() = runTest {
        client.geocodeAddress(apiKey = apiKey, query = "exception")
    }

    @Test(expected = GoogleMapsUnauthorizedException::class)
    fun `geocodePlace - when api key is incorrect, it throws unauthorized exception`() = runTest {
        client.geocodePlace(apiKey = "spam", placeId = placeId)
    }

    @Test
    fun `geocodePlace - when engine returns valid response, it returns results`() = runTest {
        assertEquals(
            GoogleMapsResult(GoogleMapsLocation(50.123456, -11.123456)),
            client.geocodePlace(apiKey = apiKey, placeId = placeId)
        )
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodePlace - when engine returns empty object, it throws not found exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "empty-object")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodePlace - when engine returns invalid response, it throws not found exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "invalid")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodePlace - when engine throws bad request, it throws not found exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "bad-request")
    }

    @Test(expected = GoogleMapsNotFoundException::class)
    fun `geocodePlace - when engine throws not found, it throws not found exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "not-found")
    }

    @Test(expected = GoogleMapsUnknownException::class)
    fun `geocodePlace - when engine throws too many requests, it throws unknown exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "too-many-requests")
    }

    @Test(expected = GoogleMapsUnknownException::class)
    fun `geocodePlace - when engine throws exception, it throws unknown exception`() = runTest {
        client.geocodePlace(apiKey = apiKey, placeId = "exception")
    }
}
