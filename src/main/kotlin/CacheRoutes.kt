package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import net.geoshare_app.lib.StatusFailed

fun Route.cacheRoutes(cache: Cache) {
    route("/v1/status/cache") {
        authenticate("status") {
            rateLimit {
                head("/connection") {
                    // Check that the connection to the Redis cache works
                    if (cache.ping()) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(StatusFailed)
                    }
                }
            }
        }
    }
}
