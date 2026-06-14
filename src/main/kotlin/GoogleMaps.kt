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
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.geoshare_app.lib.UpstreamNotFoundException
import net.geoshare_app.lib.UpstreamUnauthorizedException
import net.geoshare_app.lib.UpstreamUnknownException
import net.geoshare_app.lib.equalsDelta
import net.geoshare_app.lib.propertyAsBoolean
import net.geoshare_app.lib.propertyAsString
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

fun Application.googleMapsModule(engine: HttpClientEngine = CIO.create()) {
    val config = environment.config
    val apiKey = config.propertyAsString("googleMaps.apiKey", "googleMaps.apiKeyFile")
    val dryRun = config.propertyAsBoolean("googleMaps.dryRun", false)

    install(Resources)
    routing {
        authenticate("api") {
            route("/v1/google-maps") {
                route("/verified") {
                    rateLimit(RateLimitName("verified")) {
                        get<AddressResource> { address ->
                            call.respond(callGeocodeAddressApi(engine, apiKey, dryRun, address.query))
                        }
                        get<PlaceResource> { place ->
                            call.respond(callGeocodePlacesApi(engine, apiKey, dryRun, place.id))
                        }
                    }
                }
                route("/unverified") {
                    rateLimit(RateLimitName("unverified")) {
                        get<AddressResource> { address ->
                            call.respond(callGeocodeAddressApi(engine, apiKey, dryRun, address.query))
                        }
                        get<PlaceResource> { place ->
                            call.respond(callGeocodePlacesApi(engine, apiKey, dryRun, place.id))
                        }
                    }
                }
            }
        }
        authenticate("status") {
            route("/v1/status/google-maps") {
                rateLimit {
                    head("/connection") {
                        val res = callGeocodeAddressApi(engine, apiKey, dryRun, "Lumen Field")
                        if (res.results.firstOrNull()?.location != GoogleMapsLocation(47.5951518, -122.3316394)) {
                            call.respond(HttpStatusCode.InternalServerError, "Unexpected location")
                        } else {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                    head("/verified/geocode/address/hour") {
                        // TODO Report number of verified Google Maps Geocode Address queries
                    }
                    head("/verified/geocode/places/hour") {
                        // TODO Report number of verified Google Maps Geocode Place queries
                    }
                    head("/unverified/geocode/address/hour") {
                        // TODO Report number of unverified Google Maps Geocode Address queries
                    }
                    head("/unverified/geocode/places/hour") {
                        // TODO Report number of unverified Google Maps Geocode Place queries
                    }
                }
            }
        }
    }
}

private suspend fun callGeocodeAddressApi(
    engine: HttpClientEngine,
    apiKey: String,
    dryRun: Boolean,
    query: String,
): GoogleMapsResults =
    if (!dryRun) {
        callApi<GoogleMapsResults>(
            engine = engine,
            apiKey = apiKey,
            fieldMask = "results.location",
            "v4", "geocode", "address", query,
        )
    } else {
        GoogleMapsResults(listOf(GoogleMapsResult(GoogleMapsLocation.random())))
    }

private suspend fun callGeocodePlacesApi(
    engine: HttpClientEngine,
    apiKey: String,
    dryRun: Boolean,
    placeId: String,
): GoogleMapsResult =
    if (!dryRun) {
        callApi<GoogleMapsResult>(
            engine = engine,
            apiKey = apiKey,
            fieldMask = "location",
            "v4", "geocode", "places", placeId,
        )
    } else {
        GoogleMapsResult(GoogleMapsLocation.random())
    }

private suspend inline fun <reified T> callApi(
    engine: HttpClientEngine,
    apiKey: String,
    fieldMask: String,
    vararg path: String,
): T =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }.use { client ->
        try {
            client.get {
                url {
                    url("https://geocode.googleapis.com")
                    appendPathSegments(*path)
                }
                headers {
                    append("X-Goog-Api-Key", apiKey)
                    append("X-Goog-FieldMask", fieldMask)
                }
            }.body<T>()
        } catch (tr: ClientRequestException) {
            when (tr.response.status) {
                HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> throw UpstreamNotFoundException(tr)
                HttpStatusCode.Unauthorized -> throw UpstreamUnauthorizedException(tr)
                else -> throw UpstreamUnknownException(tr)
            }
        } catch (tr: JsonConvertException) {
            throw UpstreamNotFoundException(tr)
        } catch (tr: Exception) {
            throw UpstreamUnknownException(tr)
        }
    }
