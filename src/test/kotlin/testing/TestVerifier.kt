package net.geoshare_app.testing

import com.android.keyattestation.verifier.Verifier
import com.android.keyattestation.verifier.testing.Certs
import java.time.Instant

@Suppress("unused")
fun provideVerifier(): Verifier =
    Verifier(
        { setOf(Certs.rootAnchor) },
        { setOf() },
        { Instant.now() },
    )
