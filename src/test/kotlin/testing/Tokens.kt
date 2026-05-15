package net.geoshare_app.testing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import net.geoshare_app.fingerprint
import java.util.Date
import kotlin.time.Duration.Companion.minutes

object Tokens {
    const val JWT_SECRET = "test-secret"

    val valid by lazy {
        JWT.create()
            .withSubject(Certs.leafKey.public.fingerprint())
            .withExpiresAt(Date(System.currentTimeMillis() + 1.minutes.inWholeMilliseconds))
            .sign(Algorithm.HMAC256(JWT_SECRET))
    }

    val expired by lazy {
        JWT.create()
            .withSubject(Certs.leafKey.public.fingerprint())
            .withExpiresAt(Date(System.currentTimeMillis() - 1.minutes.inWholeMilliseconds))
            .sign(Algorithm.HMAC256(JWT_SECRET))
    }

    fun verify(token: String): DecodedJWT =
        JWT.require(Algorithm.HMAC256(JWT_SECRET)).build()
            .verify(token)
}
