package net.geoshare_app.lib

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.JWTPrincipal
import java.util.Date
import kotlin.time.Duration

enum class Device { VERIFIED, UNVERIFIED }

fun createToken(publicKeyFingerprint: String, secret: ByteArray, expire: Duration, device: Device): String =
    JWT.create()
        .withSubject(publicKeyFingerprint)
        .withClaim("device", device.name)
        .withExpiresAt(Date(System.currentTimeMillis() + expire.inWholeMilliseconds))
        .sign(Algorithm.HMAC256(secret))

fun String.toDevice(): Device? =
    try {
        Device.valueOf(this)
    } catch (_: IllegalArgumentException) {
        null
    }

fun JWTPrincipal.toDevice(): Device? =
    payload.getClaim("device")?.asString()?.toDevice()
