package net.geoshare_app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.resources.Resource
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.UpstreamNotFoundException
import net.geoshare_app.lib.UpstreamUnauthorizedException
import net.geoshare_app.lib.UpstreamUnknownException
import net.geoshare_app.lib.equalsDelta
import net.geoshare_app.lib.propertyAsBoolean
import net.geoshare_app.lib.propertyAsString
import net.geoshare_app.lib.details
import net.geoshare_app.lib.toScale
import kotlin.random.Random

@Serializable
data class GoogleMapsLocation(val latitude: Double, val longitude: Double) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GoogleMapsLocation
        return latitude.equalsDelta(other.latitude) && longitude.equalsDelta(other.longitude)
    }

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        return result
    }

    companion object {
        fun random(minLat: Double = -50.0, maxLat: Double = 80.0, minLon: Double = -180.0, maxLon: Double = 180.0) =
            GoogleMapsLocation(
                Random.nextDouble(minLat, maxLat).toScale(6),
                Random.nextDouble(minLon, maxLon).toScale(6),
            )
    }
}

@Serializable
data class GoogleMapsResult(val location: GoogleMapsLocation)

@Serializable
data class GoogleMapsResults(val results: List<GoogleMapsResult>)

@Resource("/geocode/address/{query}")
private class AddressResource(val query: String)

@Resource("/geocode/places/{id}")
private class PlaceResource(val id: String)

class GoogleMapsClient(
    private val apiKey: String,
    private val dryRun: Boolean,
    private val engine: HttpClientEngine,
    private val statsRepository: StatsRepository,
) {
    suspend fun callGeocodeAddressApi(call: ApplicationCall, query: String): GoogleMapsResults =
        if (!dryRun) {
            callApi<GoogleMapsResults>(
                call, path = listOf("v4", "geocode", "address", query), fieldMask = "results.location"
            )
        } else {
            GoogleMapsResults(listOf(GoogleMapsResult(GoogleMapsLocation.random())))
        }

    suspend fun callGeocodePlacesApi(call: ApplicationCall, placeId: String): GoogleMapsResult =
        if (!dryRun) {
            callApi<GoogleMapsResult>(
                call, path = listOf("v4", "geocode", "places", placeId), fieldMask = "location"
            )
        } else {
            GoogleMapsResult(GoogleMapsLocation.random())
        }

    private suspend inline fun <reified T> callApi(call: ApplicationCall, path: List<String>, fieldMask: String): T =
        HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }.use { client ->
            try {
                val res = client.get {
                    url {
                        url("https://geocode.googleapis.com")
                        appendPathSegments(path)
                    }
                    headers {
                        append("X-Goog-Api-Key", apiKey)
                        append("X-Goog-FieldMask", fieldMask)
                    }
                }
                val body = res.body<T>()
                // TODO Test Google Maps stats
                with(call.details) {
                    statsRepository.hashIncrease("stats:google-maps:success:$hour:by-endpoint", endpoint)
                    statsRepository.increase("stats:google-maps:success:$hour:total")
                }
                body
            } catch (tr: ClientRequestException) {
                // TODO Test Google Maps stats
                with(call.details) {
                    statsRepository.hashIncrease(
                        "stats:google-maps:exception:client-request:$hour:by-code",
                        tr.response.status.value.toString()
                    )
                    statsRepository.increase("stats:google-maps:exception:client-request:$hour:total")
                    statsRepository.increase("stats:google-maps:exception:all:$hour:total")
                }
                when (tr.response.status) {
                    HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> throw UpstreamNotFoundException(tr)
                    HttpStatusCode.Unauthorized -> throw UpstreamUnauthorizedException(tr)
                    else -> throw UpstreamUnknownException(tr)
                }
            } catch (tr: JsonConvertException) {
                // TODO Test Google Maps stats
                with(call.details) {
                    statsRepository.increase("stats:google-maps:exception:json-convert:$hour:total")
                    statsRepository.increase("stats:google-maps:exception:all:$hour:total")
                }
                throw UpstreamNotFoundException(tr)
            } catch (tr: Exception) {
                // TODO Test Google Maps stats
                with(call.details) {
                    statsRepository.increase("stats:google-maps:exception:unknown:$hour:total")
                    statsRepository.increase("stats:google-maps:exception:all:$hour:total")
                }
                throw UpstreamUnknownException(tr)
            }
        }
}

fun Application.googleMapsModule(engine: HttpClientEngine = CIO.create(), statsRepository: StatsRepository) {
    val config = environment.config

    val googleMapsClient = GoogleMapsClient(
        apiKey = config.propertyAsString("googleMaps.apiKey", "googleMaps.apiKeyFile"),
        dryRun = config.propertyAsBoolean("googleMaps.dryRun", false),
        engine = engine,
        statsRepository = statsRepository,
    )

    install(Resources)
    routing {
        route("/v1/google-maps") {
            authenticate("dispatch") {
                rateLimit {
                    get<AddressResource> { address ->
                        // TODO Test Google Maps Geocode Address dispatch
                        when (call.authentication.principal<JWTPrincipal>()?.toDevice()) {
                            Device.UNVERIFIED -> call.respondRedirect("/v1/google-maps/unverified/geocode/address/${address.query}")
                            Device.VERIFIED -> call.respondRedirect("/v1/google-maps/verified/geocode/address/${address.query}")
                            null -> call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                    get<PlaceResource> { place ->
                        // TODO Test Google Maps Geocode Place dispatch
                        when (call.authentication.principal<JWTPrincipal>()?.toDevice()) {
                            Device.UNVERIFIED -> call.respondRedirect("/v1/google-maps/unverified/geocode/places/${place.id}")
                            Device.VERIFIED -> call.respondRedirect("/v1/google-maps/verified/geocode/places/${place.id}")
                            null -> call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }
            route("/verified") {
                authenticate("verified") {
                    rateLimit(RateLimitName("verified")) {
                        get<AddressResource> { address ->
                            call.respond(googleMapsClient.callGeocodeAddressApi(call, address.query))
                        }
                        get<PlaceResource> { place ->
                            call.respond(googleMapsClient.callGeocodePlacesApi(call, place.id))
                        }
                    }
                }
            }
            route("/unverified") {
                authenticate("unverified") {
                    rateLimit(RateLimitName("unverified")) {
                        get<AddressResource> { address ->
                            call.respond(googleMapsClient.callGeocodeAddressApi(call, address.query))
                        }
                        get<PlaceResource> { place ->
                            call.respond(googleMapsClient.callGeocodePlacesApi(call, place.id))
                        }
                    }
                }
            }
        }
        route("/v1/status/google-maps") {
            authenticate("status") {
                rateLimit {
                    head("/connection") {
                        // TODO Test
                        val res = googleMapsClient.callGeocodeAddressApi(call, "Lumen Field")
                        if (res.results.firstOrNull()?.location != GoogleMapsLocation(47.5951518, -122.3316394)) {
                            call.respond(StatusFailed, "Unexpected location")
                        } else {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                    head("/geocode/address/success/verified/hour") {
                        // TODO Test
                        with(call.details) {
                            val num = statsRepository.hashGet(
                                "stats:google-maps:success:$hour:by-endpoint",
                                "google-maps-verified-address"
                            )
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/geocode/places/success/verified/hour") {
                        // TODO Test
                        with(call.details) {
                            val num = statsRepository.hashGet(
                                "stats:google-maps:success:$hour:by-endpoint",
                                "google-maps-verified-places"
                            )
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/geocode/address/success/unverified/hour") {
                        // TODO Test
                        with(call.details) {
                            val num = statsRepository.hashGet(
                                "stats:google-maps:success:$hour:by-endpoint",
                                "google-maps-unverified-address"
                            )
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/geocode/places/success/unverified/hour") {
                        // TODO Test
                        with(call.details) {
                            val num = statsRepository.hashGet(
                                "stats:google-maps:success:$hour:by-endpoint",
                                "google-maps-unverified-places"
                            )
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/exception/hour") {
                        // TODO Test
                        with(call.details) {
                            val num = statsRepository.get("stats:google-maps:exception:all:$hour:total")
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                }
            }
        }
    }
}
