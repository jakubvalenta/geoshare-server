package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GoogleMapsNotFoundException(cause: Throwable) : Exception(cause)

class GoogleMapsUnauthorizedException(cause: Throwable) : Exception(cause)

class GoogleMapsUnknownException(cause: Throwable) : Exception(cause)

@Serializable
data class GoogleMapsLocation(val latitude: Double, val longitude: Double)

@Serializable
data class GoogleMapsResult(val location: GoogleMapsLocation)

@Serializable
data class GoogleMapsResults(val results: List<GoogleMapsResult>)

interface GoogleMapsClient {
    suspend fun geocodeAddress(apiKey: String, query: String): GoogleMapsResults
    suspend fun geocodePlace(apiKey: String, placeId: String): GoogleMapsResult
}

class DefaultGoogleMapsClient(private val engine: HttpClientEngine = CIO.create()) : GoogleMapsClient {
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        callGoogleMapsApi<GoogleMapsResults>(
            "v4", "geocode", "address", query,
            engine = engine,
            apiKey = apiKey,
            fieldMask = "results.location",
        )

    override suspend fun geocodePlace(apiKey: String, placeId: String) =
        callGoogleMapsApi<GoogleMapsResult>(
            "v4", "geocode", "places", placeId,
            engine = engine,
            apiKey = apiKey,
            fieldMask = "location",
        )
}

private suspend inline fun <reified T> callGoogleMapsApi(vararg path: String, engine: HttpClientEngine, apiKey: String, fieldMask: String): T =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }.use { client ->
        try {
            client.get {
                url {
                    url("https://geocode.googleapis.com")
                    appendPathSegments(*path)
                }
                headers {
                    append("X-Goog-Api-Key", apiKey)
                    append("X-Goog-FieldMask", fieldMask)
                }
            }.body<T>()
        } catch (tr: ClientRequestException) {
            when (tr.response.status) {
                HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> throw GoogleMapsNotFoundException(tr)

                HttpStatusCode.Unauthorized -> throw GoogleMapsUnauthorizedException(tr)

                else -> throw GoogleMapsUnknownException(tr)
            }
        } catch (tr: JsonConvertException) {
            throw GoogleMapsNotFoundException(tr)
        } catch (tr: Exception) {
            throw GoogleMapsUnknownException(tr)
        }
    }

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = DefaultGoogleMapsClient()
