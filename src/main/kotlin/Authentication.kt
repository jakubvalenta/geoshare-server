@file:OptIn(ExperimentalKtorApi::class)

package net.geoshare_app

import com.android.keyattestation.verifier.VerificationResult
import com.android.keyattestation.verifier.VerifiedBootState
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.ApplicationConfigurationException
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.server.response.respond
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import net.geoshare_app.lib.SERVICE_NAME
import net.geoshare_app.lib.SigningPurpose
import net.geoshare_app.lib.base64Decode
import net.geoshare_app.lib.base64Encode
import net.geoshare_app.lib.buildSigningPayloadV1
import net.geoshare_app.lib.fingerprint
import net.geoshare_app.lib.formatHour
import net.geoshare_app.lib.orReadFile
import net.geoshare_app.lib.readCertificateFromDEROrPEM
import net.geoshare_app.lib.readPublicKeyFromDER
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.lib.verifySignature
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Date
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Implements an authentication mechanism that requires the client to register a key pair, log in with it, and receive
 * an access token to authenticate subsequent requests.
 *
 * If the key pair was generated on a physical Android device, more generous rate limits will apply to the requests. The
 * goal is therefore to allow anyone to use the server without the need for user accounts, while making it difficult to
 * abuse the server without a physical device.
 *
 * ### Authentication flow
 *
 * **1. Client requests a random single-use challenge using [generateChallenge].**
 *
 * **2. Client generates a key pair, signs the challenge, and registers using [register], which returns an access
 * token.**
 *
 * Registration consumes the challenge, including when the request validation fails. To retry, the client needs to
 * request a new challenge.
 *
 * Registration of an already registered key pair is rejected. Client must then either log in with the registered key
 * pair, or generate and register a new key pair.
 *
 * Registration expiration is configured in [AuthenticationConfig.deviceExpireSec]. The expiration is extended whenever
 * the client authenticates using [login]. So clients that use the server at least once per
 * [AuthenticationConfig.deviceExpireSec] don't have to repeatedly register, but clients that become idle are forgotten
 * and must register again.
 *
 * **3. When the access token expires, the client requests a new random single-use challenge using
 * [generateChallenge].**
 *
 * Notice that this login challenge is the same as the registration challenge. We don't need two different challenges,
 * because the signature that is sent to log in or register contains a purpose, which prevents cross-flow replay
 * (see _Signature_).
 *
 * **4. Client signs the challenge using their registered key pair and logs in using [login], which returns an access
 * token.**
 *
 * Login consumes the challenge, including when the request validation fails. To retry, the client needs to request a
 * new challenge.
 *
 * ### Signature
 *
 * Both [register] and [login] verify a signature made over the byte layout:
 *
 * ```
 * geoshare-server:v1:<purpose>:<fingerprint>:<challenge base64>
 * ```
 *
 * - `geoshare-server:v1:` is a fixed prefix; it prevents cross-service replay.
 * - `purpose` is either `login` or `registration`; it is derived from the endpoint, never from the request; it prevents
 *   cross-flow replay.
 * - `fingerprint` is the public key SHA256 hash, encoded as base64 with padding; it ties the signature to the key pair,
 *   so a signature made with one key can't be used as a signature of another key.
 * - `challenge` is the challenge returned by [generateChallenge], encoded as base64 with padding.
 *
 * The byte layout is unambiguous even without length prefixes or serialization, because the `:` separator can't appear
 * in any of the fields.
 *
 * For compatibility with older clients, a legacy signature format is still accepted while
 * [AuthenticationConfig.legacySignatureEnabled] is true. The legacy signature is made over the raw challenge bytes
 * (base64-decoded value):
 *
 * ```
 * <challenge bytes>
 * ```
 *
 * ### Access token
 *
 * Both [register] and [login] return a JWT access token on success.
 *
 * Token expiration is configured in [AuthenticationConfig.jwtExpireSec] and it is deliberately long (ca. 7 days),
 * because every login requires two extra requests (fetch challenge + signed login), which slows the client down
 * noticeably.
 *
 * Token revocation is not supported. Validation checks only the claims of the token, so a token is valid until it
 * expires, and the token expiration is the accepted damage window for a stolen token.
 *
 * If the client registered a key pair that was generated on a physical device (device with working key attestation),
 * the JWT token will contain a `device` claim with the value [Device.VERIFIED], otherwise the claim value will be
 * [Device.UNVERIFIED]. The claim is read in the rate limiting module to choose more generous or more strict limits.
 *
 * It is enforced at startup that token expiration ([AuthenticationConfig.jwtExpireSec]) is shorter than
 * registration expiration ([AuthenticationConfig.deviceExpireSec]), so that clients are forced to periodically
 * authenticate using [login], which extends their registration expiration.
 */
class Authentication(
    authenticationConfig: AuthenticationConfig,
    private val cache: Cache,
    private val certificateVerification: CertificateVerification,
    private val statsRepository: StatsRepository,
) {
    private val challengeExpire = authenticationConfig.challengeExpireSec.toDuration(DurationUnit.SECONDS)
    private val deviceExpire = authenticationConfig.deviceExpireSec.toDuration(DurationUnit.SECONDS)
    private val jwtExpire = authenticationConfig.jwtExpireSec.toDuration(DurationUnit.SECONDS)
        .also {
            if (it >= deviceExpire) {
                throw ApplicationConfigurationException("JWT expiration must be less than device expiration")
            }
        }
    private val jwtSecret = authenticationConfig.jwtSecret
        .orReadFile(authenticationConfig.jwtSecretFile)?.toByteArray()
        ?: throw ApplicationConfigurationException("Missing JWT secret or JWT secret file")
    private val legacySignatureEnabled = authenticationConfig.legacySignatureEnabled
    private val statusApiTokenHash = authenticationConfig.statusApiTokenHash
        .orReadFile(authenticationConfig.statusApiTokenHashFile)
        ?: throw ApplicationConfigurationException("Missing status API token hash or API token hash file")

    private val secureRandom = SecureRandom()

    val userScheme = jwt<User>("user") {
        verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
        validate { validateToken(it) }
        onUnauthorized = {
            val challenge = generateChallenge()
            call.respond(HttpStatusCode.Unauthorized, ChallengeResponse(challenge.base64Encode()))
        }
    }
    val statusScheme = bearer<Unit>("status") {
        validate { credential ->
            if (credential.token.toByteArray().sha256Hex() == statusApiTokenHash) {
                Unit
            } else {
                null
            }
        }
    }

    suspend fun generateChallenge(): ByteArray {
        val challenge = ByteArray(32).also { secureRandom.nextBytes(it) }
        val challengeCacheKey = challenge.sha256Hex()
        cache.set("challenge:$challengeCacheKey", "", challengeExpire)
        statsRepository.increase("stats:auth:challenge:success:${formatHour()}:total")
        return challenge
    }

    suspend fun register(
        certificateChain: List<String>,
        challengeBase64: String,
        signature: ByteArray,
    ): RegistrationResult {
        // Consume challenge
        val challenge = challengeBase64.base64Decode()
        return if (!consumeChallenge(challenge)) {
            RegistrationResult.Unauthorized("Invalid challenge")
        } else {
            // Validate certificate chain
            val certificateChain = certificateChain.map { it.base64Decode().readCertificateFromDEROrPEM() }
            when (val verificationResult = certificateVerification.verify(certificateChain)) {
                is VerificationResult.Success -> {
                    val publicKeyFingerprint = verificationResult.publicKey.fingerprint()
                    // Verify signature
                    if (
                        verifySignature(
                            purpose = SigningPurpose.REGISTRATION,
                            publicKey = verificationResult.publicKey,
                            publicKeyFingerprint = publicKeyFingerprint,
                            signature = signature,
                            challenge = challenge,
                        )
                    ) {
                        // Generate token
                        val device = if (
                            verificationResult.verifiedBootState == VerifiedBootState.VERIFIED ||
                            (verificationResult.verifiedBootState == VerifiedBootState.SELF_SIGNED &&
                                certificateVerification.isKnownBootFingerprint(verificationResult.verifiedBootFingerprint))
                        ) {
                            Device.VERIFIED
                        } else {
                            Device.UNVERIFIED
                        }
                        val token = createToken(publicKeyFingerprint, device)
                        if (registerDevice(publicKeyFingerprint, device)) {
                            RegistrationResult.Success(token)
                        } else {
                            RegistrationResult.Conflict("Already registered")
                        }
                    } else {
                        RegistrationResult.Unauthorized("Invalid signature")
                    }
                }

                is VerificationResult.PathValidationFailure
                    // Crude check for software attestation, because the key-attestation library doesn't return a
                    // specific exception. This code must be checked whenever the key-attestation is updated.
                    if verificationResult.cause.message == "Chain terminates in a software root and no matching trust anchor was found, so the chain was not validated." -> {

                    val publicKey = certificateChain.firstOrNull()?.publicKey
                    if (publicKey != null) {
                        // Verify signature
                        val publicKeyFingerprint = publicKey.fingerprint()
                        if (
                            verifySignature(
                                purpose = SigningPurpose.REGISTRATION,
                                publicKey = publicKey,
                                publicKeyFingerprint = publicKeyFingerprint,
                                signature = signature,
                                challenge = challenge,
                            )
                        ) {
                            // Generate token
                            val device = Device.UNVERIFIED
                            val token = createToken(publicKeyFingerprint, device)
                            if (registerDevice(publicKeyFingerprint, device)) {
                                RegistrationResult.Success(token)
                            } else {
                                RegistrationResult.Conflict("Already registered")
                            }
                        } else {
                            RegistrationResult.Unauthorized("Invalid signature")
                        }
                    } else {
                        RegistrationResult.Unauthorized("Invalid signature")
                    }
                }

                is VerificationResult.PathValidationFailure ->
                    RegistrationResult.Unauthorized("Path validation failure")

                is VerificationResult.ChallengeMismatch ->
                    RegistrationResult.Unauthorized("Challenge mismatch")

                is VerificationResult.ChainParsingFailure ->
                    RegistrationResult.Unauthorized("Chain parsing failure")

                is VerificationResult.ExtensionParsingFailure ->
                    RegistrationResult.Unauthorized("Extension parsing failure")

                is VerificationResult.ConstraintViolation ->
                    RegistrationResult.Unauthorized("Constraint violation")

                is VerificationResult.SoftwareAttestationUnsupported ->
                    RegistrationResult.Unauthorized("Software attestation unsupported")
            }
        }.also { authenticationResult ->
            when (authenticationResult) {
                is RegistrationResult.Conflict,
                is RegistrationResult.Unauthorized ->
                    statsRepository.increase("stats:auth:register:error:${formatHour()}:total")

                is RegistrationResult.Success ->
                    statsRepository.increase("stats:auth:register:success:${formatHour()}:total")
            }
        }
    }

    suspend fun login(challengeBase64: String, publicKey: ByteArray, signature: ByteArray): LoginResult {
        // Consume challenge
        val challenge = challengeBase64.base64Decode()
        return if (!consumeChallenge(challenge)) {
            LoginResult.Unauthorized("Invalid challenge")
        } else {
            // Validate device
            val publicKey = publicKey.readPublicKeyFromDER()
            val publicKeyFingerprint = publicKey.fingerprint()
            val device = cache.get("device:$publicKeyFingerprint")?.toDevice()
            if (device == null) {
                LoginResult.Unauthorized("Unknown device")
            } else {
                // Verify signature
                if (
                    verifySignature(
                        purpose = SigningPurpose.LOGIN,
                        publicKey = publicKey,
                        publicKeyFingerprint = publicKeyFingerprint,
                        signature = signature,
                        challenge = challenge,
                    )
                ) {
                    val token = createToken(publicKeyFingerprint, device)
                    refreshDevice(publicKeyFingerprint)
                    LoginResult.Success(token)
                } else {
                    LoginResult.Unauthorized("Invalid signature")
                }
            }
        }.also { authenticationResult ->
            when (authenticationResult) {
                is LoginResult.Unauthorized ->
                    statsRepository.increase("stats:auth:login:error:${formatHour()}:total")

                is LoginResult.Success ->
                    statsRepository.increase("stats:auth:login:success:${formatHour()}:total")
            }
        }
    }

    private suspend fun consumeChallenge(challenge: ByteArray): Boolean {
        val challengeCacheKey = challenge.sha256Hex()
        return cache.delete("challenge:$challengeCacheKey")
    }

    private suspend fun verifySignature(
        purpose: SigningPurpose,
        publicKey: PublicKey,
        publicKeyFingerprint: String,
        signature: ByteArray,
        challenge: ByteArray,
    ): Boolean {
        val payloadV1 = buildSigningPayloadV1(purpose, publicKeyFingerprint, challenge)
        if (publicKey.verifySignature(signature, payloadV1.toByteArray())) {
            return true
        }
        if (legacySignatureEnabled) {
            if (publicKey.verifySignature(signature, challenge)) {
                statsRepository.increase("stats:auth:legacy-signature:success:${formatHour()}:total")
                return true
            }
        }
        return false
    }

    private suspend fun registerDevice(publicKeyFingerprint: String, device: Device): Boolean =
        cache.setIfNotExists("device:$publicKeyFingerprint", device.name, deviceExpire)

    private suspend fun refreshDevice(publicKeyFingerprint: String) {
        cache.expire("device:$publicKeyFingerprint", deviceExpire)
    }

    private fun createToken(publicKeyFingerprint: String, device: Device): String =
        System.currentTimeMillis().let { currentTimeMillis ->
            JWT.create()
                .withSubject(publicKeyFingerprint)
                .withClaim("device", device.name)
                .withIssuedAt(Date(currentTimeMillis))
                .withIssuer(SERVICE_NAME)
                .withExpiresAt(Date(currentTimeMillis + jwtExpire.inWholeMilliseconds))
                .sign(Algorithm.HMAC256(jwtSecret))
        }

    private fun validateToken(token: JWTCredential): User? {
        if (token.issuer != SERVICE_NAME) return null
        val publicKeyFingerprint = token.subject.takeUnless { it.isNullOrEmpty() } ?: return null
        val device = token.payload.getClaim("device").asString().toDevice() ?: return null
        return User(publicKeyFingerprint = publicKeyFingerprint, device = device)
    }
}

@Serializable
data class AuthenticationConfig(
    val legacySignatureEnabled: Boolean = true,
    val challengeExpireSec: Int,
    val deviceExpireSec: Int,
    val jwtExpireSec: Int,
    val jwtSecret: String? = null,
    val jwtSecretFile: String? = null,
    val revocationListRefreshIntervalSec: Int,
    val statusApiTokenHash: String? = null,
    val statusApiTokenHashFile: String? = null,
)

enum class Device { VERIFIED, UNVERIFIED }

data class User(val publicKeyFingerprint: String, val device: Device)

sealed interface RegistrationResult {
    data class Conflict(val message: String) : RegistrationResult
    data class Success(val token: String) : RegistrationResult
    data class Unauthorized(val message: String) : RegistrationResult
}

sealed interface LoginResult {
    data class Success(val token: String) : LoginResult
    data class Unauthorized(val message: String) : LoginResult
}

fun String.toDevice(): Device? =
    try {
        Device.valueOf(this)
    } catch (_: IllegalArgumentException) {
        null
    }

@Suppress("unused")
fun provideAuthentication(
    @Property("auth") authenticationConfig: AuthenticationConfig,
    cache: Cache,
    certificateVerification: CertificateVerification,
    statsRepository: StatsRepository,
): Authentication =
    Authentication(authenticationConfig, cache, certificateVerification, statsRepository)
