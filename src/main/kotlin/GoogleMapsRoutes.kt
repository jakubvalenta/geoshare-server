@file:OptIn(ExperimentalKtorApi::class)

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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticateWith
import io.ktor.server.config.ApplicationConfigurationException
import io.ktor.server.config.property
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.UpstreamNotFoundException
import net.geoshare_app.lib.UpstreamUnauthorizedException
import net.geoshare_app.lib.UpstreamUnknownException
import net.geoshare_app.lib.details
import net.geoshare_app.lib.equalsDelta
import net.geoshare_app.lib.orReadFile
import net.geoshare_app.lib.toScale
import kotlin.random.Random

@Serializable
private data class GoogleMapsConfig(
    val apiKey: String? = null,
    val apiKeyFile: String? = null,
    val dryRun: Boolean? = null,
)

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
                with(call.details) {
                    statsRepository.hashIncrease("stats:google-maps:success:$hour:by-endpoint", endpoint)
                    statsRepository.increase("stats:google-maps:success:$hour:total")
                }
                body
            } catch (tr: ClientRequestException) {
                with(call.details) {
                    statsRepository.hashIncrease(
                        "stats:google-maps:exception:$hour:by-code", tr.response.status.value.toString()
                    )
                    statsRepository.hashIncrease("stats:google-maps:exception:$hour:by-endpoint", endpoint)
                    statsRepository.hashIncrease(
                        "stats:google-maps:exception:$hour:by-type", "client-request-exception"
                    )
                    statsRepository.increase("stats:google-maps:exception:$hour:total")
                }
                when (tr.response.status) {
                    HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> throw UpstreamNotFoundException(tr)
                    HttpStatusCode.Unauthorized -> throw UpstreamUnauthorizedException(tr)
                    else -> throw UpstreamUnknownException(tr)
                }
            } catch (tr: JsonConvertException) {
                with(call.details) {
                    statsRepository.hashIncrease("stats:google-maps:exception:$hour:by-endpoint", endpoint)
                    statsRepository.hashIncrease(
                        "stats:google-maps:exception:$hour:by-type", "json-convert-exception"
                    )
                    statsRepository.increase("stats:google-maps:exception:$hour:total")
                }
                throw UpstreamNotFoundException(tr)
            } catch (tr: Exception) {
                with(call.details) {
                    statsRepository.hashIncrease("stats:google-maps:exception:$hour:by-endpoint", endpoint)
                    statsRepository.hashIncrease(
                        "stats:google-maps:exception:$hour:by-type", "unknown"
                    )
                    statsRepository.increase("stats:google-maps:exception:$hour:total")
                }
                throw UpstreamUnknownException(tr)
            }
        }
}

fun Route.googleMapsRoutes(
    authentication: Authentication,
    engine: HttpClientEngine = CIO.create(),
    statsRepository: StatsRepository
) {
    val googleMapsConfig: GoogleMapsConfig = application.property("googleMaps")
    val googleMapsClient = GoogleMapsClient(
        apiKey = googleMapsConfig.apiKey
            .orReadFile(googleMapsConfig.apiKeyFile)
            ?: throw ApplicationConfigurationException("Missing Google Maps API key or API key file"),
        dryRun = googleMapsConfig.dryRun ?: false,
        engine = engine,
        statsRepository = statsRepository,
    )

    route("/v1/google-maps") {
        authenticateWith(authentication.userScheme) {
            rateLimit(RateLimitName("per-user")) {
                get<AddressResource> { address ->
                    call.respond(googleMapsClient.callGeocodeAddressApi(call, address.query))
                }
                get<PlaceResource> { place ->
                    call.respond(googleMapsClient.callGeocodePlacesApi(call, place.id))
                }
            }
        }
    }

    route("/v1/status/google-maps") {
        rateLimit {
            authenticateWith(authentication.statusScheme) {
                head("/connection") {
                    // Check that Google Maps geocode API returns a result, so the configured API key is correct
                    val res = googleMapsClient.callGeocodeAddressApi(call, "Lumen Field")
                    if (res.results.firstOrNull()?.location != GoogleMapsLocation(47.5951518, -122.3316394)) {
                        call.respond(StatusFailed, "Unexpected location")
                    } else {
                        call.respond(HttpStatusCode.OK)
                    }
                }
                head("/success/hour") {
                    // Check that there hasn't been too many successful API calls, which would suggest misuse
                    with(call.details) {
                        val num = statsRepository.get("stats:google-maps:success:$hour:total")
                        if (num > 100) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/exception/hour") {
                    // Check that there hasn't been too many exceptions
                    with(call.details) {
                        val num = statsRepository.get("stats:google-maps:exception:$hour:total")
                        if (num > 5) {
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
