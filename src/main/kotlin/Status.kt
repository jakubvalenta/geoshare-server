package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.details

fun Application.statusModule(cache: Cache, statsRepository: StatsRepository) {
    routing {
        route("/v1/status") {
            authenticate("status") {
                rateLimit {
                    head("/cache") {
                        if (cache.ping()) {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(StatusFailed)
                        }
                    }
                    head("/rate-limit/hour") {
                        // TODO Test rate limit status
                        with(call.details) {
                            val num = statsRepository.get("stats:rate-limit:$hour:total")
                            if (num > 0) {
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
}
