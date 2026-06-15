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
import net.geoshare_app.lib.UpstreamNotFoundException
import net.geoshare_app.lib.UpstreamUnauthorizedException
import net.geoshare_app.lib.UpstreamUnknownException
import net.geoshare_app.lib.ipToRateLimitBlock
import net.geoshare_app.lib.propertyAsDuration
import net.geoshare_app.lib.propertyAsInt
import net.geoshare_app.lib.details
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun Application.rootModule(statsRepository: StatsRepository) {
    val config = environment.config

    install(ContentNegotiation) {
        json()
    }
    install(RateLimit) {
        register {
            rateLimiter(
                limit = config.propertyAsInt("rateLimit.default.limit"),
                refillPeriod = config.propertyAsDuration("rateLimit.default.refillPeriodSec"),
            )
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.let { ipToRateLimitBlock(it) } ?: ""
            }
        }
        register(RateLimitName("register")) {
            rateLimiter(
                limit = config.propertyAsInt("rateLimit.register.limit"),
                refillPeriod = config.propertyAsDuration("rateLimit.register.refillPeriodSec"),
            )
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.let { ipToRateLimitBlock(it) } ?: ""
            }
        }
        register(RateLimitName("unverified")) {
            rateLimiter(
                limit = config.propertyAsInt("rateLimit.unverified.limit"),
                refillPeriod = config.propertyAsDuration("rateLimit.unverified.refillPeriodSec"),
            )
            requestKey { applicationCall ->
                applicationCall.principal<JWTPrincipal>()?.subject ?: ""
            }
        }
        register(RateLimitName("verified")) {
            rateLimiter(
                limit = config.propertyAsInt("rateLimit.verified.limit"),
                refillPeriod = config.propertyAsDuration("rateLimit.verified.refillPeriodSec"),
            )
            requestKey { applicationCall ->
                applicationCall.principal<JWTPrincipal>()?.subject ?: ""
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
            // TODO Test rate limit stats
            with(call.details) {
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-endpoint", endpoint)
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-ip", ip)
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-subject", subject)
                statsRepository.increase("stats:rate-limit:$hour:total")
            }
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "Too many requests. Wait for $retryAfter seconds", status = status)
        }
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
