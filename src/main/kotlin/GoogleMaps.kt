package net.geoshare_app

import io.ktor.resources.Resource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

@Resource("/google-maps/geocode/places/{id}")
private class PlaceResource(val id: String)

@Suppress("unused")
fun Application.googleMapsModule(cache: Cache, googleMapsClient: GoogleMapsClient) {
    val googleMapsApiKey = environment.config.property("googleMaps.apiKey").getString()

    install(ContentNegotiation) {
        json()
    }
    install(Resources)
    routing {
        authenticate {
            rateLimit {
                get<PlaceResource> { place ->
                    // To increase security, use hash of place id instead of the raw user-supplied place id as cache key
                    val cacheKey = place.id.sha256Hex()

                    // Try reading location from cache before calling Google Maps API
                    val cachedSerializedLocation = cache.get(cacheKey)
                    val location = if (cachedSerializedLocation != null) {
                        Json.decodeFromString<Location>(cachedSerializedLocation)
                    } else {
                        // Call Google Maps API
                        googleMapsClient.geocode(googleMapsApiKey, place.id).also {
                            // Save location to cache; to increase security, serialize it to JSON instead of storing a
                            // raw Google Maps response
                            val serializedLocation = Json.encodeToString(it)
                            cache.set(cacheKey, serializedLocation)
                        }
                    }
                    call.respond(location)
                }
            }
        }
    }
}
