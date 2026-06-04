package net.geoshare_app

import kotlin.math.abs

fun Double.equalsDelta(other: Double, epsilon: Double = 1e-9): Boolean =
    abs(this - other) < epsilon
