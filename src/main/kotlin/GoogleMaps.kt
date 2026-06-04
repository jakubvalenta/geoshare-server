package net.geoshare_app

import io.ktor.http.HttpStatusCode
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

@Resource("/v1/google-maps/status")
private class StatusResource

const val STATUS_QUERY = "Lumen Field"

fun Application.googleMapsModule(googleMapsClient: GoogleMapsClient) {
    val apiKey = environment.config.propertyOrNull("googleMaps.apiKey")?.getString()
        ?: File(environment.config.property("googleMaps.apiKeyFile").getString()).readText()

    install(Resources)
    routing {
        authenticate("api") {
            rateLimit {
                get<AddressResource> { address ->
                    call.respond(googleMapsClient.geocodeAddress(apiKey, address.query))
                }
            }
            rateLimit {
                get<PlaceResource> { place ->
                    call.respond(googleMapsClient.geocodePlace(apiKey, place.id))
                }
            }
        }
        authenticate("status") {
            rateLimit {
                get<StatusResource> {
                    val res = try {
                        googleMapsClient.geocodeAddress(apiKey, STATUS_QUERY)
                    } catch (_: GoogleMapsNotFoundException) {
                        call.respond(HttpStatusCode.InternalServerError, "Not found")
                        return@get
                    } catch (_: GoogleMapsUnauthorizedException) {
                        call.respond(HttpStatusCode.InternalServerError, "Unauthorized")
                        return@get
                    } catch (_: GoogleMapsException) {
                        call.respond(HttpStatusCode.InternalServerError, "Failed")
                        return@get
                    }
                    val firstResult = res.results.firstOrNull()
                    if (firstResult == null) {
                        call.respond(HttpStatusCode.InternalServerError, "No results")
                    } else if (firstResult.location != GoogleMapsLocation(47.5951518, -122.3316394)) {
                        call.respond(HttpStatusCode.InternalServerError, "Unexpected location")
                    } else {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }
    }
}
