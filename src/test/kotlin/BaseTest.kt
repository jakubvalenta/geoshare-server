package net.geoshare_app

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.Date
import kotlin.time.Duration.Companion.minutes

interface BaseTest {
    fun ApplicationTestBuilder.getJsonClient(): HttpClient =
        createClient {
            install(ContentNegotiation) {
                json()
            }
        }

    fun generateValidToken(publicKeyFingerprint: String = "test-public-key"): String =
        JWT.create()
            .withSubject(publicKeyFingerprint)
            .withExpiresAt(Date(System.currentTimeMillis() + 1.minutes.inWholeMilliseconds))
            .sign(Algorithm.HMAC256("test-secret"))

    fun generateExpiredToken(publicKeyFingerprint: String = "test-public-key"): String =
        JWT.create()
            .withSubject(publicKeyFingerprint)
            .withExpiresAt(Date(System.currentTimeMillis() - 1.minutes.inWholeMilliseconds))
            .sign(Algorithm.HMAC256("test-secret"))
}
