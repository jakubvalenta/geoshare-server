package net.geoshare_app.lib

import java.io.File

fun String?.orReadFile(pathname: String?): String? =
    this ?: pathname?.let { pathname -> File(pathname).readText() }
