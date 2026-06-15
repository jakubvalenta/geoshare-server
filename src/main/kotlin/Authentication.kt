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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.base64Decode
import net.geoshare_app.lib.base64Encode
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.propertyAsBytes
import net.geoshare_app.lib.propertyAsDuration
import net.geoshare_app.lib.propertyAsString
import net.geoshare_app.lib.readCertificateFromDEROrPEM
import net.geoshare_app.lib.readPublicKeyFromDER
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.lib.details
import net.geoshare_app.lib.verifySignature
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Duration

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

enum class Device { VERIFIED, UNVERIFIED }

fun Application.authenticationModule(cache: Cache, certificateVerification: CertificateVerification, statsRepository: StatsRepository) {
    val config = environment.config

    val challengeExpire = config.propertyAsDuration("auth.challengeExpireSec")
    val deviceExpire = config.propertyAsDuration("auth.deviceExpireSec")
    val jwtExpire = config.propertyAsDuration("auth.jwtExpireSec")
    val jwtSecret = config.propertyAsBytes("auth.jwtSecret","auth.jwtSecretFile")
    val revocationListRefreshInterval = config.propertyAsDuration("auth.revocationListRefreshIntervalSec")
    val statusApiTokenHash = config.propertyAsString("auth.statusApiTokenHash","auth.statusApiTokenHashFile")

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
        jwt("dispatch") {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (!credential.payload.subject.isNullOrEmpty()) {
                    JWTPrincipal(credential.payload)
                } else {
                    with(this.details) {
                        // TODO Test auth unauthorized dispatch stats
                        statsRepository.hashIncrease("stats:auth:unauthorized:$hour:by-endpoint", endpoint)
                        statsRepository.increase("stats:auth:unauthorized:$hour:total")
                    }
                    null
                }
            }
        }
        jwt("unverified") {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (
                    !credential.payload.subject.isNullOrEmpty() &&
                    credential.payload.getClaim("device").asString().toDevice() == Device.UNVERIFIED
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    with(this.details) {
                        // TODO Test auth unauthorized unverified stats
                        statsRepository.hashIncrease("stats:auth:unauthorized:$hour:by-endpoint", endpoint)
                        statsRepository.increase("stats:auth:unauthorized:$hour:total")
                    }
                    null
                }
            }
        }
        jwt("verified") {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (
                    !credential.payload.subject.isNullOrEmpty() &&
                    credential.payload.getClaim("device").asString().toDevice() == Device.VERIFIED
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    with(this.details) {
                        // TODO Test auth unauthorized verified stats
                        statsRepository.hashIncrease("stats:auth:unauthorized:$hour:by-endpoint", endpoint)
                        statsRepository.increase("stats:auth:unauthorized:$hour:total")
                    }
                    null
                }
            }
        }
        bearer("status") {
            authenticate { tokenCredential ->
                if (tokenCredential.token.toByteArray().sha256Hex() == statusApiTokenHash) {
                    true
                } else {
                    with(this.details) {
                        // TODO Test auth unauthorized status stats
                        statsRepository.hashIncrease("stats:auth:unauthorized:$hour:by-endpoint", endpoint)
                        statsRepository.increase("stats:auth:unauthorized:$hour:total")
                    }
                    null
                }
            }
        }
    }
    routing {
        rateLimit {
            post("/v1/auth/challenge") {
                val challenge = ByteArray(32).also { secureRandom.nextBytes(it) }
                val challengeCacheKey = challenge.sha256Hex()
                cache.set("challenge:$challengeCacheKey", "", challengeExpire)
                val res = ChallengeResponse(challenge.base64Encode())
                with(call.details) {
                    // TODO Test auth challenge success stats
                    statsRepository.increase("stats:auth:challenge:success:$hour:total")
                }
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
                        is VerificationResult.Success -> {
                            // Validate signature
                            if (verificationResult.publicKey.verifySignature(signature, challenge)) {
                                // Generate token
                                val publicKeyFingerprint = verificationResult.publicKey.fingerprint()
                                val device = if (
                                    verificationResult.verifiedBootState == VerifiedBootState.VERIFIED ||
                                    (verificationResult.verifiedBootState == VerifiedBootState.SELF_SIGNED &&
                                        certificateVerification.isKnownBootFingerprint(verificationResult.verifiedBootFingerprint))
                                ) {
                                    Device.VERIFIED
                                } else {
                                    Device.UNVERIFIED
                                }
                                val token = createToken(publicKeyFingerprint, jwtSecret, jwtExpire, device)
                                // Register device before deleting the challenge, so the client can retry if
                                // device registration crashes
                                cache.set("device:$publicKeyFingerprint", device.name, deviceExpire)
                                // Delete challenge only after all validations pass, so the client can retry if
                                // anything crashes
                                cache.delete("challenge:$challengeCacheKey")
                                TokenResponse(token)
                            } else {
                                ErrorResponse("Invalid signature")
                            }
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
                    is ErrorResponse -> {
                        with(call.details) {
                            // TODO Test auth register error stats
                            statsRepository.increase("stats:auth:register:error:$hour:total")
                        }
                        call.respond(HttpStatusCode.Unauthorized, res.message)
                    }
                    is TokenResponse -> {
                        with(call.details) {
                            // TODO Test auth register success stats
                            statsRepository.increase("stats:auth:register:success:$hour:total")
                        }
                        call.respond(res)
                    }
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
                    // Validate device
                    val publicKey = req.publicKey.base64Decode().readPublicKeyFromDER()
                    val publicKeyFingerprint = publicKey.fingerprint()
                    val device = cache.get("device:$publicKeyFingerprint")?.toDevice()
                    if (device == null) {
                        ErrorResponse("Unknown device")
                    } else {
                        // Validate signature
                        if (publicKey.verifySignature(signature, challenge)) {
                            val token = createToken(publicKeyFingerprint, jwtSecret, jwtExpire, device)
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
                    is ErrorResponse -> {
                        with(call.details) {
                            // TODO Test auth login error stats
                            statsRepository.increase("stats:auth:login:error:$hour:total")
                        }
                        call.respond(HttpStatusCode.Unauthorized, res.message)
                    }

                    is TokenResponse -> {
                        with(call.details) {
                            // TODO Test auth login success stats
                            statsRepository.increase("stats:auth:login:success:$hour:total")
                        }
                        call.respond(res)
                    }
                }
            }
        }

        route("/v1/status/auth") {
            authenticate("status") {
                rateLimit {
                    head("/challenge/success/hour") {
                        // TODO Test auth challenge success status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:challenge:success:$hour:total")
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/login/success/hour") {
                        // TODO Test auth login success status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:login:success:$hour:total")
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/login/error/hour") {
                        // TODO Test auth login error status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:login:error:$hour:total")
                            if (num > 10) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/register/success/hour") {
                        // TODO Test auth register success status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:register:success:$hour:total")
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/register/error/hour") {
                        // TODO Test auth register error status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:register:error:$hour:total")
                            if (num > 10) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                    head("/unauthorized/hour") {
                        // TODO Test auth unauthorized status
                        with(call.details) {
                            val num = statsRepository.get("stats:auth:unauthorized:$hour:total")
                            if (num > 100) {
                                call.respond(StatusFailed, num)
                            } else {
                                call.respond(num)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun String.toDevice(): Device? =
    try {
        Device.valueOf(this)
    } catch (_: IllegalArgumentException) {
        null
    }

fun JWTPrincipal.toDevice(): Device? =
    payload.getClaim("device")?.asString()?.toDevice()

private fun createToken(publicKeyFingerprint: String, secret: ByteArray, expire: Duration, device: Device): String =
    JWT.create()
        .withSubject(publicKeyFingerprint)
        .withClaim("device", device.name)
        .withExpiresAt(Date(System.currentTimeMillis() + expire.inWholeMilliseconds))
        .sign(Algorithm.HMAC256(secret))
