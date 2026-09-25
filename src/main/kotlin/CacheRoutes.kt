@file:OptIn(ExperimentalKtorApi::class)

package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticateWith
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import net.geoshare_app.lib.StatusFailed

fun Route.cacheRoutes(authentication: Authentication, cache: Cache) {
    route("/v1/status/cache") {
        rateLimit {
            authenticateWith(authentication.statusScheme) {
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
