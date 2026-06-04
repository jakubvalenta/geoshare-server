package net.geoshare_app

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Returns the network block of [ip] used as a rate-limiting key.
 *
 * Limiting by block rather than individual address prevents trivial evasion by rotating IPs within the same allocation:
 * - IPv4: /24 -- the last octet is zeroed (standard ISP segment boundary)
 * - IPv6: /64 -- the last 8 bytes are zeroed (smallest per-user allocation)
 *
 * If [ip] cannot be parsed as a numeric IP address, it is returned unchanged so that rate limiting still applies rather
 * than silently bypassing it.
 *
 * Examples:
 * ```
 * ipToRateLimitBlock("203.0.113.47") // "203.0.113.0"
 * ipToRateLimitBlock("2001:db8:dead:beef:abc::1") // "2001:db8:dead:beef"
 * ```
 */
fun ipToRateLimitBlock(ip: String): String {
    val address = try {
        // getByName() performs no DNS lookup for numeric IP literals
        InetAddress.getByName(ip)
    } catch (_: UnknownHostException) {
        return ip
    }
    val bytes = address.address
    when (address) {
        is Inet4Address -> bytes.fill(0, fromIndex = 3, toIndex = 4) // /24
        is Inet6Address -> bytes.fill(0, fromIndex = 8, toIndex = 16) // /64
        else -> return ip
    }
    return InetAddress.getByAddress(bytes).hostAddress
}
