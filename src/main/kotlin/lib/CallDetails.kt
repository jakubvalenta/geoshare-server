package net.geoshare_app.lib

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CallDetails(private val call: ApplicationCall) {
    val hour: String by lazy {
        LocalDateTime.now().format(hourFormat)
    }
    val path by lazy {
        call.request.path()
    }
    val endpoint by lazy {
        when {
            path.startsWith("/v1/google-maps/geocode/address/") -> "google-maps-dispatch-address"
            path.startsWith("/v1/google-maps/geocode/places/") -> "google-maps-dispatch-places"
            path.startsWith("/v1/google-maps/unverified/geocode/address/") -> "google-maps-unverified-address"
            path.startsWith("/v1/google-maps/unverified/geocode/places/") -> "google-maps-unverified-places"
            path.startsWith("/v1/google-maps/verified/geocode/address/") -> "google-maps-verified-address"
            path.startsWith("/v1/google-maps/verified/geocode/places/") -> "google-maps-verified-places"
            path == "/v1/auth/challenge" -> "auth-challenge"
            path == "/v1/auth/login" -> "auth-login"
            path == "/v1/auth/register" -> "auth-register"
            path.startsWith("/v1/auth/status") -> "status"
            else -> "unknown"
        }
    }
    val ip by lazy {
        call.request.header("X-Real-Ip") ?: "unknown"
    }
    val subject by lazy {
        call.principal<JWTPrincipal>()?.subject ?: "unknown"
    }

    private companion object {
        private val hourFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")
    }
}

val ApplicationCall.details get() = CallDetails(this)
