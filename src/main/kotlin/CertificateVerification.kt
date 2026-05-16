package net.geoshare_app

import com.android.keyattestation.verifier.GoogleTrustAnchors
import com.android.keyattestation.verifier.Verifier
import java.time.Instant

interface CertificateVerification {
    suspend fun getVerifier(): Verifier
}

class CertificateVerificationImpl(val certificateRevocation: CertificateRevocation) : CertificateVerification {
    override suspend fun getVerifier(): Verifier {
        val revokedSerials = certificateRevocation.getRevokedSerials()
        return Verifier(
            GoogleTrustAnchors,
            { revokedSerials },
            { Instant.now() },
        )
    }
}

@Suppress("unused")
fun provideCertificateVerification(certificateRevocation: CertificateRevocation): CertificateVerification =
    CertificateVerificationImpl(certificateRevocation)
