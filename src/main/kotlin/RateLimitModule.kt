package net.geoshare_app

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.config.property
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlinx.serialization.Serializable
import net.geoshare_app.lib.ipToRateLimitBlock
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
private data class RateLimitCategoryConfig(
    val limit: Int,
    val refillPeriodSec: Int,
)

@Serializable
private data class RateLimitConfig(
    val default: RateLimitCategoryConfig,
    val perUser: RateLimitCategoryConfig,
    val register: RateLimitCategoryConfig,
)

fun Application.rateLimitModule() {
    val rateLimitConfig: RateLimitConfig = property("rateLimit")

    install(RateLimit) {
        register {
            rateLimiter(
                limit = rateLimitConfig.default.limit,
                refillPeriod = rateLimitConfig.default.refillPeriodSec.toDuration(DurationUnit.SECONDS),
            )
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.takeIf { it.isNotBlank() }
                    ?.let { "ip:${ipToRateLimitBlock(it)}" } ?: "shared"
            }
        }
        register(RateLimitName("per-user")) {
            rateLimiter(
                limit = rateLimitConfig.perUser.limit,
                refillPeriod = rateLimitConfig.perUser.refillPeriodSec.toDuration(DurationUnit.SECONDS),
            )
            requestKey { applicationCall ->
                applicationCall.principal<User>()?.publicKeyFingerprint?.takeIf { it.isNotBlank() }?.let { "user:$it" }
                    ?: applicationCall.request.headers["X-Real-Ip"]?.takeIf { it.isNotBlank() }
                        ?.let { "ip:${ipToRateLimitBlock(it)}" } ?: "shared"
            }
            requestWeight { applicationCall, _ ->
                when (applicationCall.principal<User>()?.device) {
                    Device.VERIFIED -> 1
                    Device.UNVERIFIED, null -> 2
                }
            }
        }
        register(RateLimitName("register")) {
            rateLimiter(
                limit = rateLimitConfig.register.limit,
                refillPeriod = rateLimitConfig.register.refillPeriodSec.toDuration(DurationUnit.SECONDS),
            )
            requestKey { applicationCall ->
                applicationCall.request.headers["X-Real-Ip"]?.takeIf { it.isNotBlank() }
                    ?.let { "ip:${ipToRateLimitBlock(it)}" } ?: "shared"
            }
        }
    }
}
