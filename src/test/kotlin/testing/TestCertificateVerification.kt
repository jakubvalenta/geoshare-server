package net.geoshare_app.testing

import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.testing.Certs
import net.geoshare_app.CertificateRevocation
import net.geoshare_app.CertificateVerification
import java.time.Instant

class TestCertificateVerification(val certificateRevocation: CertificateRevocation) : CertificateVerification {
    override suspend fun getVerifier(): Verifier {
        val revokedSerials = certificateRevocation.getRevokedSerials()
        return Verifier(
            { setOf(Certs.rootAnchor) },
            { revokedSerials },
            { Instant.now() },
        )
    }
}

@Suppress("unused")
fun provideCertificateVerification(certificateRevocation: CertificateRevocation): CertificateVerification =
    TestCertificateVerification(certificateRevocation)
