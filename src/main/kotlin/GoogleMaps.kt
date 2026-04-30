package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import io.ktor.http.headers
import io.ktor.resources.Resource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Resource("/google-maps/geocode/places/{id}")
class GeocodePlaceId(val id: String)

@Serializable
data class Location(val latitude: Long, val longitude: Long)

@Suppress("unused")
fun Application.googleMapsModule(cache: Cache) {
    val googleMapsApiKey = environment.config.property("googleMaps.apiKey").getString()
    val client = HttpClient(CIO) {
        expectSuccess = true
    }

    install(ContentNegotiation) {
        json()
    }
    routing {
        authenticate {
            rateLimit {
                get<GeocodePlaceId> { geocodePlaceId ->
                    // To increase security, use hash of place id as cache key instead of plain place id
                    val placeIdHash = geocodePlaceId.id.sha256Hex()

                    // Try reading location from cache before calling Google Maps API
                    val cachedLocation = cache.get(placeIdHash)?.let {
                        try {
                            Json.decodeFromString<Location>(it)
                        } catch (tr: IllegalArgumentException) {
                            null
                        }
                    }
                    if (cachedLocation != null) {
                        call.respond(cachedLocation)
                    }

                    // Call Google Maps API
                    val res = client.get("https://geocode.googleapis.com") {
                        url {
                            appendPathSegments("v4", "geocode", "places", geocodePlaceId.id)
                        }
                        headers {
                            append("X-Goog-Api-Key", googleMapsApiKey)
                            append("X-Goog-FieldMask", "location")
                        }
                    }
                    val location: Location = res.body<Location>()

                    // Save location to cache
                    val serializedLocation = try {
                        Json.encodeToString(location)
                    } catch (tr: SerializationException) {
                        null
                    }
                    if (serializedLocation != null) {
                        cache.set(placeIdHash, serializedLocation)
                    }

                    call.respond(location)
                }
            }
        }
    }
}
