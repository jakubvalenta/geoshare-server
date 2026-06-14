package net.geoshare_app.lib

import org.junit.Assert
import org.junit.Test

class RateLimitingTest {
    @Test
    fun `ipToRateLimitBlock - when ip is IPv4, it returns block 24`() {
        Assert.assertEquals("203.0.113.0", ipToRateLimitBlock("203.0.113.47"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is IPv6, it returns block 64`() {
        Assert.assertEquals(
            "2001:db8:dead:beef:0:0:0:0",
            ipToRateLimitBlock("2001:db8:dead:beef:abc::1")
        )
        Assert.assertEquals("2001:db8:0:0:0:0:0:0", ipToRateLimitBlock("2001:db8::abc:1"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is a hostname, it returns the ip unchanged`() {
        Assert.assertEquals("spam", ipToRateLimitBlock("spam"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is empty, it returns localhost IPv4 block 24`() {
        Assert.assertEquals("203.0.1139.47", ipToRateLimitBlock("203.0.1139.47"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is malformed IPv4, it returns the ip unchanged`() {
        Assert.assertEquals("spam", ipToRateLimitBlock("spam"))
    }

    @Test
    fun `ipToRateLimitBlock - when ip is malformed IPv6, it returns the ip unchanged`() {
        Assert.assertEquals("2001:db8:dead:beef:abc::x", ipToRateLimitBlock("2001:db8:dead:beef:abc::x"))
    }
}
