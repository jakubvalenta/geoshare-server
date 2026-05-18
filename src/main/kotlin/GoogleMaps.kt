package net.geoshare_app

import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import java.io.File

@Resource("/v1/google-maps/geocode/address/{query}")
private class AddressResource(val query: String)

@Resource("/v1/google-maps/geocode/places/{id}")
private class PlaceResource(val id: String)

fun Application.googleMapsModule(googleMapsClient: GoogleMapsClient) {
    val googleMapsApiKey = environment.config.propertyOrNull("googleMaps.apiKey")?.getString()
        ?: File(environment.config.property("googleMaps.apiKeyFile").getString()).readText()

    install(Resources)
    routing {
        authenticate {
            rateLimit {
                // TODO Test GET /v1/google-maps/geocode/address/{query}
                get<AddressResource> { address ->
                    call.respond(googleMapsClient.geocodeAddress(googleMapsApiKey, address.query))
                }
            }
            rateLimit {
                get<PlaceResource> { place ->
                    call.respond(googleMapsClient.geocodePlace(googleMapsApiKey, place.id))
                }
            }
        }
    }
}
