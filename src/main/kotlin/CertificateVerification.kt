package net.geoshare_app

import com.android.keyattestation.verifier.GoogleTrustAnchors
import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.getGoogleRevocationStatusFromWeb
import java.time.Instant

interface CertificateVerification {
    val cache: Cache

    suspend fun getVerifier(): Verifier

    suspend fun fetchRevokedSerials(): Set<String>

    suspend fun getRevokedSerials(): Set<String> =
        cache.get(REVOKED_SERIALS_CACHE_KEY)?.split(REVOKED_SERIALS_CACHE_VALUE_SEPARATOR)?.toSet() ?: emptySet()

    suspend fun setRevokedSerials(revokedSerials: Set<String>) {
        cache.set(REVOKED_SERIALS_CACHE_KEY, revokedSerials.joinToString(REVOKED_SERIALS_CACHE_VALUE_SEPARATOR))
    }

    private companion object {
        private const val REVOKED_SERIALS_CACHE_KEY = "revoked-serials"
        private const val REVOKED_SERIALS_CACHE_VALUE_SEPARATOR = ","
    }
}

class CertificateVerificationImpl(override val cache: Cache) : CertificateVerification {
    override suspend fun getVerifier(): Verifier {
        val revokedSerials = getRevokedSerials()
        return Verifier(
            GoogleTrustAnchors,
            { revokedSerials },
            { Instant.now() },
        )
    }

    /**
     * @throws [Exception] Will throw exception if download fails.
     */
    override suspend fun fetchRevokedSerials() = getGoogleRevocationStatusFromWeb()
}

@Suppress("unused")
fun provideCertificateVerification(cache: Cache): CertificateVerification =
    CertificateVerificationImpl(cache)
