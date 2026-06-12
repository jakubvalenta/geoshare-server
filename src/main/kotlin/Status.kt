package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.head
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.statusModule(cache: Cache) {
    routing {
        authenticate("status") {
            rateLimit(RateLimitName("login")) {
                head("/v1/status") {
                    // This status check just checks that Redis connection is okay. We could extend it in the future,
                    // for example by checking whether there has been a successful login or registration recently
                    if (cache.ping()) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, "Failed")
                    }
                }
            }
        }
    }
}
