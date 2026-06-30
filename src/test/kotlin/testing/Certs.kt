package net.geoshare_app.testing

import net.geoshare_app.testing.CertLists.REVOKED_SERIAL_NUMBER
import net.geoshare_app.testing.KeyAttestationCertFactory.Companion.SELF_SIGNED_KEY_DESCRIPTION_EXT

private val certFactory = KeyAttestationCertFactory()

/**
 * @see [com.android.keyattestation.verifier.testing.Certs]
 */
object Certs {
    val factoryIntermediate = certFactory.factoryIntermediate
    val factoryAttestation = certFactory.factoryAttestation
    val intermediateKey = certFactory.intermediateKey
    val leafKey = certFactory.leafKey
}

/**
 * @see [com.android.keyattestation.verifier.testing.CertLists]
 */
object CertLists {
    /**
     * A chain that is missing the leaf certificate.
     */
    val noLeaf by lazy {
        listOf(Certs.factoryAttestation, Certs.factoryIntermediate, certFactory.root)
    }

    /**
     * A valid TEE factory provisioned chain.
     */
    @JvmStatic
    val validFactoryProvisioned by lazy {
        listOf(
            certFactory.generateLeafCert(),
            Certs.factoryAttestation,
            Certs.factoryIntermediate,
            certFactory.root,
        )
    }

    /**
     * A chain created on a device with locked bootloader and a custom AVB key.
     */
    @JvmStatic
    val selfSigned by lazy {
        listOf(
            certFactory.generateLeafCert(extension = SELF_SIGNED_KEY_DESCRIPTION_EXT),
            Certs.factoryAttestation,
            Certs.factoryIntermediate,
            certFactory.root,
        )
    }

    @JvmField val REVOKED_SERIAL_NUMBER = 42.toBigInteger()

    /**
     * A chain where the attestation certificate has [REVOKED_SERIAL_NUMBER].
     */
    @JvmStatic
    val revoked by lazy {
        listOf(
            certFactory.generateLeafCert(),
            certFactory.generateAttestationCert(serialNumber = REVOKED_SERIAL_NUMBER),
            Certs.factoryIntermediate,
            certFactory.root,
        )
    }
}
