package net.geoshare_app

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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Resource("/google-maps/geocode/places/{id}")
class GeocodePlaceId(val id: String)

@Suppress("unused")
fun Application.googleMapsModule(cache: Cache, googleMapsClient: GoogleMapsClient) {
    val googleMapsApiKey = environment.config.property("googleMaps.apiKey").getString()

    install(ContentNegotiation) {
        json()
    }
    routing {
        authenticate {
            rateLimit {
                get<GeocodePlaceId> { geocodePlaceId ->
                    // Use hash of place id as cache key to increase security by not directly storing user-provided data
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
                    val location = googleMapsClient.geocode(googleMapsApiKey, geocodePlaceId.id)

                    // Save location to cache; to increase security, serialize it to JSON instead of storing a raw
                    // Google Maps response
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
