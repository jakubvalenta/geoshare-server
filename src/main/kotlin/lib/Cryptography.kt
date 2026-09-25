package net.geoshare_app.lib

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

const val SERVICE_NAME = "geoshare-server"

enum class SigningPurpose(val value: String) {
    LOGIN("login"),
    REGISTRATION("registration"),
}

fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

fun ByteArray.base64Encode(): String =
    Base64.getEncoder().encodeToString(this)

fun String.base64Decode(): ByteArray =
    Base64.getDecoder().decode(this)

fun ByteArray.readCertificateFromDEROrPEM(): X509Certificate =
    CertificateFactory.getInstance("X.509")
        .generateCertificate(inputStream()) as X509Certificate

fun ByteArray.readPublicKeyFromDER(): PublicKey =
    KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(this))

fun PublicKey.fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .base64Encode()

fun PublicKey.verifySignature(signature: ByteArray, data: ByteArray): Boolean =
    Signature.getInstance(@Suppress("GrazieInspectionRunner", "SpellCheckingInspection") "SHA256withECDSA").run {
        initVerify(this@verifySignature)
        update(data)
        verify(signature)
    }

fun PrivateKey.sign(data: ByteArray): ByteArray =
    Signature.getInstance(@Suppress("GrazieInspectionRunner", "SpellCheckingInspection") "SHA256withECDSA").run {
        initSign(this@sign)
        update(data)
        sign()
    }

fun buildSigningPayloadV1(purpose: SigningPurpose, publicKeyFingerprint: String, challenge: ByteArray): String {
    val separator = ':'
    val fields = listOf(SERVICE_NAME, "v1", purpose.value, publicKeyFingerprint, challenge.base64Encode())
    require(fields.none { separator in it }) { "Payload fields must not contain '$separator'" }
    return fields.joinToString(separator.toString())
}
