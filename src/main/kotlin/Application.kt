package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Suppress("unused")
fun Application.rootModule() {
    install(Authentication) {
        jwt {
            TODO()
        }
    }
    install(RateLimit) {
        register {
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
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
