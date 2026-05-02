package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class GoogleMapsResponseException(cause: Throwable) : Exception(cause)

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

    override suspend fun geocode(googleMapsApiKey: String, placeId: String): Location {
        val res = httpClient.get("https://geocode.googleapis.com") {
            url {
                appendPathSegments("v4", "geocode", "places", placeId)
            }
            headers {
                append("X-Goog-Api-Key", googleMapsApiKey)
                append("X-Goog-FieldMask", "location")
            }
        }
        return try {
            res.body()
        } catch (tr: SerializationException) {
            throw GoogleMapsResponseException(tr)
        }
    }
}

@Suppress("unused")
fun provideGoogleMapsClient(): GoogleMapsClient = GoogleMapsClientImpl()
