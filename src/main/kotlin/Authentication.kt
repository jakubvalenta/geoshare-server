package net.geoshare_app

import com.android.keyattestation.verifier.GoogleTrustAnchors
import com.android.keyattestation.verifier.VerificationResult
import com.android.keyattestation.verifier.VerifiedBootState
import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.challengecheckers.ChallengeMatcher
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Serializable
data class ChallengeResponse(val challenge: String)

@Serializable
data class RegisterRequest(val challenge: String, val signature: String, val certificateChain: List<String>)

@Serializable
data class LoginRequest(val challenge: String, val signature: String, val publicKey: String)

sealed interface AuthenticationResponse

@Serializable
data class ErrorResponse(val message: String) : AuthenticationResponse

@Serializable
data class TokenResponse(val token: String) : AuthenticationResponse

private fun createToken(publicKeyFingerprint: String, jwtSecret: String, expire: Duration = 24.hours): String =
    JWT.create()
        .withSubject(publicKeyFingerprint)
        .withExpiresAt(Date(System.currentTimeMillis() + expire.inWholeMilliseconds))
        .sign(Algorithm.HMAC256(jwtSecret))

@Suppress("unused")
fun Application.authenticationModule(cache: Cache) {
    val deviceExpire = 90.days
    val tokenExpire = 24.hours
    val jwtSecret = environment.config.property("jwt.secret").getString()

    val secureRandom = SecureRandom()
    val verifier = Verifier(
        GoogleTrustAnchors,
        { setOf() }, // TODO Revoked serials source
        { Instant.now() },
    )

    install(Authentication) {
        jwt {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (!credential.payload.subject.isNullOrEmpty()) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
    routing {
        rateLimit {
            post("/v1/auth/challenge") {
                val randomBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
                val challenge = randomBytes.base64Encode()
                val challengeCacheKey = challenge.sha256Hex()
                cache.set("challenge:$challengeCacheKey", "", 2.minutes)
                val res = ChallengeResponse(challenge)
                call.respond(res)
            }
        }

        rateLimit(RateLimitName("register")) {
            post("/v1/auth/register") {
                val req = call.receive<RegisterRequest>()
                val challenge = req.challenge.base64Decode()
                val challengeCacheKey = challenge.sha256Hex()
                val signature = req.signature.base64Decode()

                // Validate challenge
                val res = if (cache.get("challenge:$challengeCacheKey") == null) {
                    ErrorResponse("Invalid challenge")
                } else {
                    // Validate certificate chain
                    val certificateChain = req.certificateChain.map { it.base64Decode().readCertificateFromDEROrPEM() }
                    val challengeChecker = ChallengeMatcher(challenge)
                    when (val verificationResult = verifier.verify(certificateChain, challengeChecker)) {
                        is VerificationResult.Success -> {
                            when (verificationResult.verifiedBootState) {
                                VerifiedBootState.VERIFIED -> {
                                    // Validate signature
                                    if (verificationResult.publicKey.verifySignature(signature, challenge)) {
                                        // Generate token
                                        val publicKeyFingerprint = verificationResult.publicKey.fingerprint()
                                        val token = createToken(publicKeyFingerprint, jwtSecret, tokenExpire)
                                        // Register device before deleting the challenge, so the client can retry if
                                        // device registration crashes
                                        cache.set("device:$publicKeyFingerprint", "", deviceExpire)
                                        // Delete challenge only after all validations pass, so the client can retry if
                                        // anything crashes
                                        cache.delete("challenge:$challengeCacheKey")
                                        TokenResponse(token)
                                    } else {
                                        ErrorResponse("Invalid signature")
                                    }
                                }

                                VerifiedBootState.SELF_SIGNED ->
                                    throw NotImplementedError()

                                else ->
                                    ErrorResponse("Invalid certificate chain")
                            }
                        }

                        is VerificationResult.ChallengeMismatch ->
                            ErrorResponse("Invalid certificate chain")

                        is VerificationResult.PathValidationFailure ->
                            ErrorResponse("Path validation failure chain")

                        is VerificationResult.ChainParsingFailure ->
                            ErrorResponse("Chain parsing failure")

                        is VerificationResult.ExtensionParsingFailure ->
                            ErrorResponse("Extension parsing failure")

                        is VerificationResult.ConstraintViolation ->
                            ErrorResponse("Constraint violation")

                        is VerificationResult.SoftwareAttestationUnsupported ->
                            ErrorResponse("Software attestation unsupported")
                    }
                }

                when (res) {
                    is ErrorResponse -> call.respond(HttpStatusCode.Unauthorized, res)
                    is TokenResponse -> call.respond(res)
                }
            }
        }

        rateLimit {
            post("/v1/auth/login") {
                val req = call.receive<LoginRequest>()
                val challenge = req.challenge.base64Decode()
                val challengeCacheKey = challenge.sha256Hex()
                val signature = req.signature.base64Decode()

                // Validate challenge
                val res = if (cache.get("challenge:$challengeCacheKey") == null) {
                    ErrorResponse("Invalid challenge")
                } else {
                    // Validate public key is registered
                    val publicKey = req.publicKey.base64Decode().readPublicKeyFromDER()
                    val publicKeyFingerprint = publicKey.fingerprint()
                    if (cache.get("device:$publicKeyFingerprint") == null) {
                        ErrorResponse("Unknown device")
                    } else {
                        // Validate signature
                        if (publicKey.verifySignature(signature, challenge)) {
                            val token = createToken(publicKeyFingerprint, jwtSecret, tokenExpire)
                            // Refresh device TTL, so active devices never expire
                            cache.expire("device:$publicKeyFingerprint", deviceExpire)
                            // Delete challenge only after all validations pass, so the client can retry if anything
                            // crashes
                            cache.delete("challenge:$challengeCacheKey")
                            TokenResponse(token)
                        } else {
                            ErrorResponse("Invalid signature")
                        }
                    }
                }

                call.respond(res)
            }
        }
    }
}
