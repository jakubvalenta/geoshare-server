package net.geoshare_app.lib

import io.ktor.server.config.ApplicationConfig
import java.io.File
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

fun ApplicationConfig.propertyAsString(path: String): String =
    property(path).getString()

fun ApplicationConfig.propertyAsString(path: String, filePath: String): String =
    propertyOrNull(path)?.getString()
        ?: File(property(filePath).getString()).readText()

fun ApplicationConfig.propertyAsBytes(path: String, filePath: String): ByteArray =
    propertyOrNull(path)?.getString()?.toByteArray()
        ?: File(property(filePath).getString()).readBytes()

fun ApplicationConfig.propertyAsInt(path: String): Int =
    propertyAsString(path).toInt()

fun ApplicationConfig.propertyAsBoolean(path: String, default: Boolean): Boolean =
    propertyOrNull(path)?.getString()?.toBoolean() ?: default

fun ApplicationConfig.propertyAsDuration(path: String, unit: DurationUnit = DurationUnit.SECONDS): Duration =
    propertyAsString(path).toInt().toDuration(unit)
