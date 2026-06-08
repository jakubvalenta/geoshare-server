package net.geoshare_app

import java.security.MessageDigest
import java.util.Base64

fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

fun ByteArray.base64Encode(): String =
    Base64.getEncoder().encodeToString(this)

fun String.base64Decode(): ByteArray =
    Base64.getDecoder().decode(this)
