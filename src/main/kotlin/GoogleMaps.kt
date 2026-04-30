package net.geoshare_app

import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing

@Resource("/v4/geocode/places/{id}")
class GeocodePlaceId(val id: String)

@Suppress("unused")
fun Application.googleMapsModule() {
    routing {
        authenticate {
            rateLimit {
                get<GeocodePlaceId> {
                    call.respondText("Hello ${it.id}")
                }
            }
        }
    }
}
