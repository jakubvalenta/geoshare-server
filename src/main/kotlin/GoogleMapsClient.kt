package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class GoogleMapsException(message: String, cause: Throwable) : Exception(message, cause)

@Serializable
data class Location(val latitude: Double, val longitude: Double)

interface GoogleMapsClient {
    suspend fun geocode(googleMapsApiKey: String, placeId: String): Location
}

class GoogleMapsClientImpl : GoogleMapsClient {
    private val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        expectSuccess = true
    }

    override suspend fun geocode(googleMapsApiKey: String, placeId: String) =
        try {
            httpClient.get("https://geocode.googleapis.com") {
                url {
                    appendPathSegments("v4", "geocode", "places", placeId)
                }
                headers {
                    append("X-Goog-Api-Key", googleMapsApiKey)
                    append("X-Goog-FieldMask", "location")
                }
            }.body<Location>()
        } catch (tr: ClientRequestException) {
            throw GoogleMapsException("Client request exception", tr)
        } catch (tr: SerializationException) {
            throw GoogleMapsException("Serialization exception", tr)
        }
}

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = GoogleMapsClientImpl()
