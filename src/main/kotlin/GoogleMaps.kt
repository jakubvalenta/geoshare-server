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

@Resource("/google-maps/geocode/places/{id}")
class GeocodePlaceId(val id: String)

@Serializable
data class Location(val latitude: Long, val longitude: Long)

@Suppress("unused")
fun Application.googleMapsModule() {
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
                    val res = client.get("https://geocode.googleapis.com") {
                        url {
                            appendPathSegments("v4", "geocode", "places", geocodePlaceId.id)
                        }
                        headers {
                            append("X-Goog-Api-Key", googleMapsApiKey)
                            append("X-Goog-FieldMask", "location")
                        }
                    }
                    val location: Location = res.body()
                    call.respond(location)
                }
            }
        }
    }
}
