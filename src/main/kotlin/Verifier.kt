package net.geoshare_app

import com.android.keyattestation.verifier.GoogleTrustAnchors
import com.android.keyattestation.verifier.Verifier
import java.time.Instant

@Suppress("unused")
fun provideVerifier(): Verifier =
    Verifier(
        GoogleTrustAnchors,
        { setOf() }, // TODO Revoked serials source
        { Instant.now() },
    )
