package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import net.geoshare_app.lib.UpstreamNotFoundException
import net.geoshare_app.lib.UpstreamUnauthorizedException
import net.geoshare_app.lib.UpstreamUnknownException
import net.geoshare_app.lib.details

fun Application.statusPagesModule(statsRepository: StatsRepository) {
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
            with(call.details) {
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-endpoint", endpoint)
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-ip", ip)
                statsRepository.hashIncrease("stats:rate-limit:$hour:by-subject", subject)
                statsRepository.increase("stats:rate-limit:$hour:total")
            }
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "Too many requests. Wait for $retryAfter seconds", status = status)
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            with(call.details) {
                statsRepository.hashIncrease("stats:auth:unauthorized:$hour:by-endpoint", endpoint)
                statsRepository.increase("stats:auth:unauthorized:$hour:total")
            }
            // Don't call call.respond(), so that a previously set response is used and not overwritten
        }
    }
}
