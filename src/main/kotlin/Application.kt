package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun Application.rootModule() {
    val defaultLimit = environment.config.property("rateLimit.defaultLimit").getString()
        .toInt()
    val defaultRefillPeriod = environment.config.property("rateLimit.defaultRefillPeriodSec").getString()
        .toInt().seconds
    val loginLimit = environment.config.property("rateLimit.loginLimit").getString()
        .toInt()
    val loginRefillPeriod = environment.config.property("rateLimit.loginRefillPeriodSec").getString()
        .toInt().seconds
    val registerLimit = environment.config.property("rateLimit.registerLimit").getString()
        .toInt()
    val registerRefillPeriod = environment.config.property("rateLimit.registerRefillPeriodSec").getString()
        .toInt().seconds

    install(ContentNegotiation) {
        json()
    }
    install(RateLimit) {
        register {
            rateLimiter(limit = defaultLimit, refillPeriod = defaultRefillPeriod)
            requestKey { applicationCall ->
                // TODO Test rate limiting based on JWT subject
                applicationCall.principal<JWTPrincipal>()?.subject ?: ""
            }
        }
        register(RateLimitName("login")) {
            rateLimiter(limit = loginLimit, refillPeriod = loginRefillPeriod)
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.let { ipToRateLimitBlock(it) } ?: ""
            }
        }
        register(RateLimitName("register")) {
            rateLimiter(limit = registerLimit, refillPeriod = registerRefillPeriod)
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.let { ipToRateLimitBlock(it) } ?: ""
            }
        }
    }
    install(StatusPages) {
        exception<UpstreamNotFoundException> { call, _ ->
            call.respondText(text = "Not found", status = HttpStatusCode.NotFound)
        }
        exception<UpstreamUnauthorizedException> { call, cause ->
            call.application.environment.log.error("Upstream unauthorized exception", cause)
            call.respondText(text = "Upstream request failed", status = HttpStatusCode.InternalServerError)
        }
        exception<UpstreamUnknownException> { call, cause ->
            call.application.environment.log.error("Upstream unknown exception", cause)
            call.respondText(text = "Upstream request failed", status = HttpStatusCode.InternalServerError)
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "Too many requests. Wait for $retryAfter seconds", status = status)
        }
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
