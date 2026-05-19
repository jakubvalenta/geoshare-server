package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun Application.rootModule() {
    val defaultRefillPeriod = environment.config.property("rateLimit.defaultRefillPeriodSec").getString()
        .toInt().seconds
    val registerRefillPeriod = environment.config.property("rateLimit.registerRefillPeriodSec").getString()
        .toInt().seconds

    install(ContentNegotiation) {
        json()
    }
    install(RateLimit) {
        register {
            rateLimiter(limit = 5, refillPeriod = defaultRefillPeriod)
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Forwarded-For"] ?: ""
            }
        }
        register(RateLimitName("register")) {
            rateLimiter(limit = 5, refillPeriod = registerRefillPeriod)
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Forwarded-For"] ?: ""
            }
        }
    }
    install(StatusPages) {
        exception<GoogleMapsException> { call, cause ->
            call.application.environment.log.error("Google Maps request failed", cause)
            call.respondText(text = "500: Google Maps request failed.", status = HttpStatusCode.InternalServerError)
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "429: Too many requests. Wait for $retryAfter seconds.", status = status)
        }
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
