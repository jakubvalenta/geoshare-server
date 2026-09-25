package net.geoshare_app.lib

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.path
import net.geoshare_app.User

class CallDetails(private val call: ApplicationCall) {
    val hour: String by lazy {
        formatHour()
    }
    val path by lazy {
        call.request.path()
    }
    val endpoint by lazy {
        when {
            path.startsWith("/v1/google-maps/geocode/address/") -> "google-maps-address"
            path.startsWith("/v1/google-maps/geocode/places/") -> "google-maps-places"
            path == "/v1/auth/challenge" -> "auth-challenge"
            path == "/v1/auth/login" -> "auth-login"
            path == "/v1/auth/register" -> "auth-register"
            path.startsWith("/v1/status") -> "status"
            path == "/test" -> "test"
            else -> "unknown"
        }
    }
    val ip by lazy {
        call.request.header("X-Real-Ip") ?: "unknown"
    }
    val subject by lazy {
        call.principal<User>()?.publicKeyFingerprint ?: "unknown"
    }
}

val ApplicationCall.details get() = CallDetails(this)
