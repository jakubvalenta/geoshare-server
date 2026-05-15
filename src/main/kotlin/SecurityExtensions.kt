package net.geoshare_app

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec

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
    Signature.getInstance("SHA256withECDSA").run {
        initVerify(this@verifySignature)
        update(data)
        verify(signature)
    }

fun PrivateKey.sign(data: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").run {
        initSign(this@sign)
        update(data)
        sign()
    }
