package net.geoshare_app.testing

import net.geoshare_app.Cache
import net.geoshare_app.CertificateRevocation

class TestCertificateRevocation(override val cache: Cache) : CertificateRevocation {
    override suspend fun getRevokedSerials() = setOf(
        CertLists.REVOKED_SERIAL_NUMBER.toString(16),
    )

    override fun downloadRevokedSerials() = null
}

@Suppress("unused")
fun provideCertificateRevocation(cache: Cache): CertificateRevocation =
    TestCertificateRevocation(cache)
