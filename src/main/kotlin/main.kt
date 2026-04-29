package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Resource("/v4/geocode/places/{id}")
class GeocodePlaceId(val id: String)

@Suppress("unused")
fun Application.rootModule() {
    install(RateLimit) {
        register {
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Forwarded-For"] ?: ""
            }
        }
    }
    install(Resources)
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "429: Too many requests. Wait for $retryAfter seconds.", status = status)
        }
    }
    routing {
        rateLimit {
            get<GeocodePlaceId> {
                call.respondText("Hello ${it.id}")
            }
        }
    }
}
