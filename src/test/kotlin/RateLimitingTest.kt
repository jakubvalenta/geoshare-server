package net.geoshare_app

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitingTest {
    @Test
    fun `ipToRateLimitBlock - when ip is IPv4, it returns block 24`() {
        assertEquals("203.0.113.0", ipToRateLimitBlock("203.0.113.47"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is IPv6, it returns block 64`() {
        assertEquals("2001:db8:dead:beef:0:0:0:0", ipToRateLimitBlock("2001:db8:dead:beef:abc::1"))
        assertEquals("2001:db8:0:0:0:0:0:0", ipToRateLimitBlock("2001:db8::abc:1"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is invalid, it returns the ip unchanged`() {
        assertEquals("spam", ipToRateLimitBlock("spam"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is empty, it returns localhost IPv4 block 24`() {
        assertEquals("127.0.0.0", ipToRateLimitBlock(""))
    }
}
