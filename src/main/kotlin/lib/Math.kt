package net.geoshare_app.lib

import java.math.RoundingMode
import kotlin.math.abs

fun Double.equalsDelta(other: Double, epsilon: Double = 1e-9): Boolean =
    abs(this - other) < epsilon

fun Double.toScale(scale: Int) = this.toBigDecimal().setScale(scale, RoundingMode.HALF_UP).toDouble()
