package net.geoshare_app

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.config.property
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.toDuration

fun Application.authenticationModule(certificateVerification: CertificateVerification) {
    val authenticationConfig: AuthenticationConfig = property("auth")
    val revocationListRefreshInterval = authenticationConfig.revocationListRefreshIntervalSec
        .toDuration(DurationUnit.SECONDS)

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
}
