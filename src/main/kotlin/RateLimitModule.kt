package net.geoshare_app

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import net.geoshare_app.lib.ipToRateLimitBlock
import net.geoshare_app.lib.propertyAsDuration
import net.geoshare_app.lib.propertyAsInt

fun Application.rateLimitModule() {
    val config = environment.config

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
}
