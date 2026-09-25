package net.geoshare_app

import io.ktor.server.config.ApplicationConfigurationException
import net.geoshare_app.testing.FakeCache
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AuthenticationTest {
    private val cache = FakeCache()
    private val certificateVerification = CertificateVerificationImpl(cache)
    private val statsRepository = StatsRepository(cache)

    @Test
    fun `authentication config - when jwt expiration is greater than or equal to device expiration, it throws an exception`() {
        assertFailsWith<ApplicationConfigurationException> {
            Authentication(
                authenticationConfig = AuthenticationConfig(
                    challengeExpireSec = 9,
                    deviceExpireSec = 5,
                    jwtExpireSec = 5,
                    jwtSecret = "test",
                    statusApiTokenHash = "test",
                    revocationListRefreshIntervalSec = 9,
                ),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
        }
        assertFailsWith<ApplicationConfigurationException> {
            Authentication(
                authenticationConfig = AuthenticationConfig(
                    challengeExpireSec = 9,
                    deviceExpireSec = 3,
                    jwtExpireSec = 5,
                    jwtSecret = "test",
                    statusApiTokenHash = "test",
                    revocationListRefreshIntervalSec = 9,
                ),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
        }
        Authentication(
            authenticationConfig = AuthenticationConfig(
                challengeExpireSec = 9,
                deviceExpireSec = 6,
                jwtExpireSec = 5,
                jwtSecret = "test",
                statusApiTokenHash = "test",
                revocationListRefreshIntervalSec = 9,
            ),
            cache = cache,
            certificateVerification = certificateVerification,
            statsRepository = statsRepository,
        )
    }

    @Test
    fun `authentication config - when both jwt secret and jwt secret file are missing, it throws an exception`() {
        assertFailsWith<ApplicationConfigurationException> {
            Authentication(
                authenticationConfig = AuthenticationConfig(
                    challengeExpireSec = 9,
                    deviceExpireSec = 9,
                    jwtExpireSec = 5,
                    jwtSecret = null,
                    statusApiTokenHash = "test",
                    revocationListRefreshIntervalSec = 9,
                ),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
        }
    }

    @Test
    fun `authentication config - when both status api token hash and status api token hash file are missing, it throws an exception`() {
        assertFailsWith<ApplicationConfigurationException> {
            Authentication(
                authenticationConfig = AuthenticationConfig(
                    challengeExpireSec = 9,
                    deviceExpireSec = 9,
                    jwtExpireSec = 5,
                    jwtSecret = "test",
                    statusApiTokenHash = null,
                    revocationListRefreshIntervalSec = 9,
                ),
                cache = cache,
                certificateVerification = certificateVerification,
                statsRepository = statsRepository,
            )
        }
    }
}
