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
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        expectSuccess = true
    }

    // TODO Test geocodeAddress
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        try {
            httpClient.get {
                url {
                    url("https://geocode.googleapis.com")
                    appendPathSegments("v4", "geocode", "address", query)
                }
                headers {
                    append("X-Goog-Api-Key", apiKey)
                    append("X-Goog-FieldMask", "results.location")
                }
            }.body<GoogleMapsResults>()
        } catch (tr: ClientRequestException) {
            throw GoogleMapsException("Client request exception", tr)
        } catch (tr: SerializationException) {
            throw GoogleMapsException("Serialization exception", tr)
        }

    override suspend fun geocodePlace(apiKey: String, id: String) =
        try {
            httpClient.get {
                url {
                    url("https://geocode.googleapis.com")
                    appendPathSegments("v4", "geocode", "places", id)
                }
                headers {
                    append("X-Goog-Api-Key", apiKey)
                    append("X-Goog-FieldMask", "location")
                }
            }.body<GoogleMapsResult>()
        } catch (tr: ClientRequestException) {
            throw GoogleMapsException("Client request exception", tr)
        } catch (tr: SerializationException) {
            throw GoogleMapsException("Serialization exception", tr)
        }
}

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = GoogleMapsClientImpl()
