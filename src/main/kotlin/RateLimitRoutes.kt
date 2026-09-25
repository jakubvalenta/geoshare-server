@file:OptIn(ExperimentalKtorApi::class)

package net.geoshare_app

import io.ktor.server.auth.authenticateWith
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.details

fun Route.rateLimitRoutes(authentication: Authentication, statsRepository: StatsRepository) {
    route("/v1/status/rate-limit") {
        rateLimit {
            authenticateWith(authentication.statusScheme) {
                head("/hour") {
                    // Check that there hasn't been too many rate limited requests
                    with(call.details) {
                        val num = statsRepository.get("stats:rate-limit:$hour:total")
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
