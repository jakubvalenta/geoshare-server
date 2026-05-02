package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.apikey.apiKey
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Suppress("unused")
fun Application.rootModule() {
    install(Authentication) {
        apiKey {
            validate { keyFromHeader ->
                transaction {
                    ApiKeys
                        .select(ApiKeys.id)
                        .where {
                            ApiKeys.keyHash eq keyFromHeader.sha256Hex() and
                                ApiKeys.revokedAt.isNull() and
                                (ApiKeys.expiresAt.isNull() or (ApiKeys.expiresAt greaterEq System.currentTimeMillis()))
                        }
                        .count()
                        .takeIf { it > 0 }
                }
            }
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
    install(Resources)
    install(StatusPages) {
        exception<GoogleMapsResponseException> { call, cause ->
            call.respondText(text = "500: Invalid Google Maps response.", status = HttpStatusCode.InternalServerError)
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "429: Too many requests. Wait for $retryAfter seconds.", status = status)
        }
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
