package net.geoshare_app.lib

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

private val simpleIPv4Regex = Regex("""^[\d.]+$""")

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
 * ipToRateLimitBlock("2001:db8:dead:beef:abc::1") // "2001:db8:dead:beef:0:0:0:0"
 * ```
 */
fun ipToRateLimitBlock(ip: String): String {
    if (!simpleIPv4Regex.matches(ip) && !ip.contains(":")) {
        // Make sure the input is an IPv4 or IPv6 address, so that getByName() only creates an address object and
        // doesn't perform an actual DNS lookup. This check accepts malformed IP addresses, because they will be caught
        // by getByName().
        return ip
    }
    val address = try {
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
