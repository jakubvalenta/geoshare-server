package net.geoshare_app

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

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
