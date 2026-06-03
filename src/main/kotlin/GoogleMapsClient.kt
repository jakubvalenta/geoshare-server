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

class GoogleMapsNotFoundException(message: String, cause: Throwable) : Exception(message, cause)

class GoogleMapsUnauthorizedException(message: String, cause: Throwable) : Exception(message, cause)

class GoogleMapsUnknownException(message: String, cause: Throwable) : Exception(message, cause)

@Serializable
data class GoogleMapsLocation(val latitude: Double, val longitude: Double)

@Serializable
data class GoogleMapsResult(val location: GoogleMapsLocation)

@Serializable
data class GoogleMapsResults(val results: List<GoogleMapsResult>)

interface GoogleMapsClient {
    suspend fun geocodeAddress(apiKey: String, query: String): GoogleMapsResults
    suspend fun geocodePlace(apiKey: String, id: String): GoogleMapsResult
}

class DefaultGoogleMapsClient(private val engine: HttpClientEngine = CIO.create()) : GoogleMapsClient {
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        callGoogleMapsApi<GoogleMapsResults>(
            "v4", "geocode", "address", query,
            engine = engine,
            apiKey = apiKey,
            fieldMask = "results.location",
        )

    override suspend fun geocodePlace(apiKey: String, id: String) =
        callGoogleMapsApi<GoogleMapsResult>(
            "v4", "geocode", "places", id,
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
                HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> throw GoogleMapsNotFoundException("Not found", tr)

                HttpStatusCode.Unauthorized -> throw GoogleMapsUnauthorizedException("Not found", tr)

                else -> throw GoogleMapsUnknownException("Client request exception", tr)
            }
        } catch (tr: JsonConvertException) {
            throw GoogleMapsNotFoundException("Not found", tr)
        } catch (tr: Exception) {
            throw GoogleMapsUnknownException("Unknown exception", tr)
        }
    }

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = DefaultGoogleMapsClient()
