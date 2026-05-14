package net.geoshare_app

import java.util.Base64

fun String.sha256Hex(): String =
    this.toByteArray().sha256Hex()

fun String.base64Decode(): ByteArray =
    Base64.getDecoder().decode(this)
