package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class GoogleMapsException(message: String, cause: Throwable) : Exception(message, cause)

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

class GoogleMapsClientImpl : GoogleMapsClient {
    // TODO Test geocodeAddress
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        callGoogleMapsApi<GoogleMapsResults>(
            "v4", "geocode", "address", query,
            apiKey = apiKey,
            fieldMask = "results.location",
        )

    override suspend fun geocodePlace(apiKey: String, id: String) =
        callGoogleMapsApi<GoogleMapsResult>(
            "v4", "geocode", "places", id,
            apiKey = apiKey,
            fieldMask = "location",
        )
}

private suspend inline fun <reified T> callGoogleMapsApi(vararg path: String, apiKey: String, fieldMask: String): T =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        expectSuccess = true
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
            throw GoogleMapsException("Client request exception", tr)
        } catch (tr: SerializationException) {
            throw GoogleMapsException("Serialization exception", tr)
        }
    }

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = GoogleMapsClientImpl()
