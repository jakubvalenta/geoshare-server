package net.geoshare_app

import com.android.keyattestation.verifier.VerificationResult
import com.android.keyattestation.verifier.VerifiedBootState
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

private fun createToken(publicKeyFingerprint: String, jwtSecret: ByteArray, expire: Duration): String =
    JWT.create()
        .withSubject(publicKeyFingerprint)
        .withExpiresAt(Date(System.currentTimeMillis() + expire.inWholeMilliseconds))
        .sign(Algorithm.HMAC256(jwtSecret))

fun Application.authenticationModule(cache: Cache, certificateVerification: CertificateVerification) {
    val challengeExpire = environment.config.property("auth.challengeExpireSec").getString().toInt().seconds
    val deviceExpire = environment.config.property("auth.deviceExpireSec").getString().toInt().seconds
    val jwtExpire = environment.config.property("auth.jwtExpireSec").getString().toInt().seconds
    val jwtSecret = environment.config.propertyOrNull("auth.jwtSecret")?.getString()?.toByteArray()
        ?: File(environment.config.property("auth.jwtSecretFile").getString()).readBytes()
    val revocationListRefreshInterval = environment.config.property("auth.revocationListRefreshIntervalSec")
        .getString().toInt().seconds
    val statusApiTokenHash = environment.config.propertyOrNull("googleMaps.statusApiTokenHash")?.getString()
        ?: File(environment.config.property("googleMaps.statusApiTokenHashFile").getString()).readText()

    val secureRandom = SecureRandom()

    launch {
        while (isActive) {
            try {
                log.info("Refreshing revoked certificates")
                certificateVerification.setRevokedSerials(
                    certificateVerification.fetchRevokedSerials()
                )
                log.info("Refreshed revoked certificates")
            } catch (e: Exception) {
                log.error("Failed to refresh revoked certificates", e)
            }
            // Don't wrap delay in try-catch, so that the launched coroutine can be canceled
            delay(revocationListRefreshInterval)
        }
    }

    install(Authentication) {
        jwt("api") {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (!credential.payload.subject.isNullOrEmpty()) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
        bearer("status") {
            authenticate { tokenCredential ->
                if (tokenCredential.token.toByteArray().sha256Hex() == statusApiTokenHash) true else null
            }
        }
    }
    routing {
        rateLimit(RateLimitName("login")) {
            post("/v1/auth/challenge") {
                val challenge = ByteArray(32).also { secureRandom.nextBytes(it) }
                val challengeCacheKey = challenge.sha256Hex()
                cache.set("challenge:$challengeCacheKey", "", challengeExpire)
                val res = ChallengeResponse(challenge.base64Encode())
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
                    val verifier = certificateVerification.getVerifier()
                    val certificateChain = req.certificateChain.map { it.base64Decode().readCertificateFromDEROrPEM() }
                    when (val verificationResult = verifier.verify(certificateChain)) {
                        is VerificationResult.Success ->
                            if (
                                verificationResult.verifiedBootState == VerifiedBootState.VERIFIED ||
                                (verificationResult.verifiedBootState == VerifiedBootState.SELF_SIGNED
                                    && verificationResult.verifiedBootFingerprint in certificateVerification.verifiedBootFingerprints)
                            ) {
                                // Validate signature
                                if (verificationResult.publicKey.verifySignature(signature, challenge)) {
                                    // Generate token
                                    val publicKeyFingerprint = verificationResult.publicKey.fingerprint()
                                    val token = createToken(publicKeyFingerprint, jwtSecret, jwtExpire)
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
                            } else {
                                ErrorResponse("Invalid certificate chain")
                            }

                        is VerificationResult.ChallengeMismatch ->
                            ErrorResponse("Challenge mismatch")

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
                    is ErrorResponse -> call.respond(HttpStatusCode.Unauthorized, res.message)
                    is TokenResponse -> call.respond(res)
                }
            }
        }

        rateLimit(RateLimitName("login")) {
            post("/v1/auth/login") {
                val req = call.receive<LoginRequest>()
                val challenge = req.challenge.base64Decode()
                val challengeCacheKey = challenge.sha256Hex()
                val signature = req.signature.base64Decode()

                // Validate challenge
                val res = if (cache.get("challenge:$challengeCacheKey") == null) {
                    ErrorResponse("Invalid challenge")
                } else {
                    // Validate device
                    val publicKey = req.publicKey.base64Decode().readPublicKeyFromDER()
                    val publicKeyFingerprint = publicKey.fingerprint()
                    if (cache.get("device:$publicKeyFingerprint") == null) {
                        ErrorResponse("Unknown device")
                    } else {
                        // Validate signature
                        if (publicKey.verifySignature(signature, challenge)) {
                            val token = createToken(publicKeyFingerprint, jwtSecret, jwtExpire)
                            // Refresh device expiration, so active devices never expire
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

                when (res) {
                    is ErrorResponse -> call.respond(HttpStatusCode.Unauthorized, res.message)
                    is TokenResponse -> call.respond(res)
                }
            }
        }
        authenticate("status") {
            rateLimit(RateLimitName("login")) {
                head("/v1/auth/status") {
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
