package net.geoshare_app.testing

import com.android.keyattestation.verifier.VerificationResult
import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.testing.Certs
import net.geoshare_app.Cache
import net.geoshare_app.CertificateVerification
import java.security.cert.X509Certificate
import java.time.Instant

class TestCertificateVerification(override val cache: Cache) : CertificateVerification {
    override suspend fun verify(chain: List<X509Certificate>): VerificationResult {
        val revokedSerials = getRevokedSerials()
        val verifier = Verifier(
            { setOf(Certs.rootAnchor) },
            { revokedSerials },
            { Instant.now() },
        )
        return verifier.verify(chain)
    }

    override suspend fun fetchRevokedSerials() = setOf(CertLists.REVOKED_SERIAL_NUMBER.toString(16))
}
