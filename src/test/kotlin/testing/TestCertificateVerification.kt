package net.geoshare_app.testing

import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.testing.Certs
import net.geoshare_app.Cache
import net.geoshare_app.CertificateVerification
import java.time.Instant

class TestCertificateVerification(override val cache: Cache) : CertificateVerification {
    override suspend fun getVerifier(): Verifier {
        val revokedSerials = getRevokedSerials()
        return Verifier(
            { setOf(Certs.rootAnchor) },
            { revokedSerials },
            { Instant.now() },
        )
    }

    override suspend fun fetchRevokedSerials() = setOf(CertLists.REVOKED_SERIAL_NUMBER.toString(16))
}
