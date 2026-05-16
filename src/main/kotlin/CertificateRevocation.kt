package net.geoshare_app

import com.android.keyattestation.verifier.getGoogleRevocationStatusFromWeb
import kotlin.time.Duration.Companion.hours

interface CertificateRevocation {
    val cache: Cache

    suspend fun getRevokedSerials(): Set<String> =
        cache.get(CACHE_KEY)?.split(CACHE_VALUE_SEPARATOR)?.toSet() ?: emptySet()

    suspend fun refreshRevokedSerials() {
        downloadRevokedSerials()?.also {
            cache.set(CACHE_KEY, it.joinToString(CACHE_VALUE_SEPARATOR), 24.hours)
        }
    }

    fun downloadRevokedSerials(): Set<String>?

    private companion object {
        private const val CACHE_KEY = "revoked-serials"
        private const val CACHE_VALUE_SEPARATOR = ","
    }
}

class CertificateRevocationImpl(override val cache: Cache) : CertificateRevocation {
    override fun downloadRevokedSerials() =
        try {
            getGoogleRevocationStatusFromWeb()
        } catch (_: Exception) {
            // Failed to download revocation list
            // TODO Add logging
            null
        }
}

@Suppress("unused")
fun provideCertificateRevocation(cache: Cache): CertificateRevocation =
    CertificateRevocationImpl(cache)
