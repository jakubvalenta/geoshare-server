package net.geoshare_app.testing

import com.android.keyattestation.verifier.AuthorizationList
import com.android.keyattestation.verifier.KeyDescription
import com.android.keyattestation.verifier.Origin
import com.android.keyattestation.verifier.RootOfTrust
import com.android.keyattestation.verifier.SecurityLevel
import com.android.keyattestation.verifier.VerifiedBootState
import com.android.keyattestation.verifier.testing.FakeCalendar
import com.android.keyattestation.verifier.testing.FakeSecureRandom
import com.android.keyattestation.verifier.testing.subject
import com.google.protobuf.ByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * @see [com.android.keyattestation.verifier.testing.KeyAttestationCertFactory]
 */
@Suppress("InconsistentCommentForJavaParameter", "SpellCheckingInspection")
class KeyAttestationCertFactory(val fakeCalendar: FakeCalendar = FakeCalendar.DEFAULT) {
    private val ecKeyPairGenerator =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), FakeSecureRandom())
        }

    val rootKey: KeyPair = ecKeyPairGenerator.generateKeyPair()
    val intermediateKey: KeyPair = ecKeyPairGenerator.generateKeyPair()
    val attestationKey: KeyPair = ecKeyPairGenerator.generateKeyPair()
    val leafKey: KeyPair = ecKeyPairGenerator.generateKeyPair()

    val root: X509Certificate = generateRootCertificate()
    val factoryIntermediate = generateIntermediateCertificate()
    val factoryAttestation = generateAttestationCert()

    internal fun generateRootCertificate(
        keyPair: KeyPair = rootKey,
        subject: X500Name = RKP_ROOT_SUBJECT,
    ) =
        generateCertificate(
            keyPair.public,
            keyPair.private,
            subject = subject,
            issuer = subject,
            serialNumber = BigInteger.valueOf(0xca11cafe),
            notBefore = fakeCalendar.longAgo(),
            notAfter = fakeCalendar.farInTheFuture(),
            extensions = listOf(BASIC_CONSTRAINTS_EXT),
        )

    internal fun generateIntermediateCertificate(
        publicKey: PublicKey = intermediateKey.public,
        signingKey: PrivateKey = rootKey.private,
        subject: X500Name = X500Name("SERIALNUMBER=e18c4f2ca699739a, T=TEE"),
        issuer: X500Name = this.root.subject,
    ) =
        generateCertificate(
            publicKey,
            signingKey,
            subject,
            issuer,
            serialNumber = BigInteger.valueOf(0x1234567890),
            notBefore = fakeCalendar.lastWeek(),
            notAfter = fakeCalendar.nextWeek(),
            extensions = listOf(BASIC_CONSTRAINTS_EXT),
        )

    internal fun generateAttestationCert(
        signingKey: PrivateKey = intermediateKey.private,
        subject: X500Name = X500Name("serialNumber=decafbad"),
        issuer: X500Name = factoryIntermediate.subject,
        serialNumber: BigInteger = BigInteger.valueOf(0xcafbad),
        notBefore: Date = fakeCalendar.lastWeek(),
        notAfter: Date = fakeCalendar.nextWeek(),
        extraExtension: Extension? = null,
    ) =
        generateCertificate(
            attestationKey.public,
            signingKey,
            subject,
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            extensions = listOfNotNull(BASIC_CONSTRAINTS_EXT, extraExtension),
        )

    internal fun generateLeafCert(
        publicKey: PublicKey = leafKey.public,
        signingKey: PrivateKey = attestationKey.private,
        subject: X500Name = X500Name("CN=Android Keystore Key"),
        issuer: X500Name = this.factoryAttestation.subject,
        notBefore: Date = this.fakeCalendar.lastWeek(),
        notAfter: Date = this.fakeCalendar.nextWeek(),
        extension: Extension? = KEY_DESCRIPTION_EXT,
    ): X509Certificate =
        generateCertificate(
            publicKey,
            signingKey,
            subject,
            issuer,
            serialNumber = BigInteger.ONE,
            notBefore = notBefore,
            notAfter = notAfter,
            extensions = extension?.let { listOf(it) } ?: emptyList(),
        )

    private fun generateCertificate(
        publicKey: PublicKey,
        signingKey: PrivateKey,
        subject: X500Name,
        issuer: X500Name,
        serialNumber: BigInteger,
        notBefore: Date,
        notAfter: Date,
        extensions: List<Extension> = emptyList(),
    ): X509Certificate {
        val builder =
            JcaX509v3CertificateBuilder(issuer, serialNumber, notBefore, notAfter, subject, publicKey)
        extensions.forEach(builder::addExtension)
        return builder.sign(signingKey.asSigner())
    }

    companion object {
        val BASIC_CONSTRAINTS_EXT =
            Extension(
                Extension.basicConstraints,
                /* critical= */ true,
                BasicConstraints(/* cA= */ true).encoded,
            )

        val KEY_DESCRIPTION_EXT =
            KeyDescription(
                attestationVersion = 1.toBigInteger(),
                attestationSecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
                keyMintVersion = 1.toBigInteger(),
                keyMintSecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
                attestationChallenge = ByteString.copyFromUtf8("A random 40-byte challenge for no reason"),
                uniqueId = ByteString.empty(),
                softwareEnforced = AuthorizationList(),
                hardwareEnforced =
                    AuthorizationList(
                        rootOfTrust =
                            RootOfTrust(
                                ByteString.copyFromUtf8("bootKey"),
                                false,
                                VerifiedBootState.VERIFIED,
                                ByteString.copyFromUtf8("bootHash"),
                            ),
                        origin = Origin.GENERATED,
                    ),
            )
                .asExtension()

        val SELF_SIGNED_KEY_DESCRIPTION_EXT =
            KeyDescription(
                attestationVersion = 1.toBigInteger(),
                attestationSecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
                keyMintVersion = 1.toBigInteger(),
                keyMintSecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
                attestationChallenge = ByteString.copyFromUtf8("A random 40-byte challenge for no reason"),
                uniqueId = ByteString.empty(),
                softwareEnforced = AuthorizationList(),
                hardwareEnforced =
                    AuthorizationList(
                        rootOfTrust =
                            RootOfTrust(
                                ByteString.copyFromUtf8("bootKey"),
                                false,
                                VerifiedBootState.SELF_SIGNED,
                                ByteString.copyFromUtf8("bootHash"),
                            ),
                        origin = Origin.GENERATED,
                    ),
            )
                .asExtension()

        val RKP_ROOT_SUBJECT = X500Name("CN=Test Key Attestation CA1, OU=Android, O=Google LLC, C=US")
    }
}

private fun PrivateKey.asSigner(): ContentSigner {
    val signatureAlgorithm =
        when (this) {
            is ECPrivateKey -> "SHA256WithECDSA"
            is RSAPrivateKey -> "SHA256WithRSA"
            else -> throw IllegalArgumentException("Unsupported private key type: ${this::class}")
        }
    return JcaContentSignerBuilder(signatureAlgorithm).build(this)
}

private fun X509CertificateHolder.asX509Certificate() =
    JcaX509CertificateConverter().getCertificate(this)

private fun X509v3CertificateBuilder.sign(signer: ContentSigner) =
    this.build(signer).asX509Certificate()

private fun FakeCalendar.longAgo(): Date = today.minusYears(5).toDate()

private fun FakeCalendar.farInTheFuture(): Date = today.plusYears(5).toDate()

private fun Instant.toDate() = Date.from(this)

private fun LocalDate.toDate() = this.atStartOfDay(ZoneId.of("UTC")).toInstant().toDate()
