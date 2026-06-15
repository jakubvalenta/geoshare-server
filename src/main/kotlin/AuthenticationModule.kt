package net.geoshare_app

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.geoshare_app.lib.Device
import net.geoshare_app.lib.propertyAsBytes
import net.geoshare_app.lib.propertyAsDuration
import net.geoshare_app.lib.propertyAsString
import net.geoshare_app.lib.sha256Hex
import net.geoshare_app.lib.toDevice

fun Application.authenticationModule(certificateVerification: CertificateVerification) {
    val config = environment.config

    val jwtSecret = config.propertyAsBytes("auth.jwtSecret","auth.jwtSecretFile")
    val revocationListRefreshInterval = config.propertyAsDuration("auth.revocationListRefreshIntervalSec")
    val statusApiTokenHash = config.propertyAsString("auth.statusApiTokenHash","auth.statusApiTokenHashFile")

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
                    null
                }
            }
        }
        bearer("status") {
            authenticate { tokenCredential ->
                if (tokenCredential.token.toByteArray().sha256Hex() == statusApiTokenHash) {
                    true
                } else {
                    null
                }
            }
        }
    }
}
