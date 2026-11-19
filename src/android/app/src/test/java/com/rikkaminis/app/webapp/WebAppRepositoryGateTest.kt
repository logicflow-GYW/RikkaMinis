package com.rikkaminis.app.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WebAppRepositoryGateTest {
    private lateinit var uninitialized: Any

    @Test
    fun `degraded boot states do not even attempt to read the lateinit`() {
        var reads = 0
        val read = { reads++; uninitialized }
        assertNull(webAppRepositoryIfReady(true, false, false, read))
        assertNull(webAppRepositoryIfReady(false, true, false, read)) // safe mode cleared, init still skipped
        assertNull(webAppRepositoryIfReady(false, false, true, read))
        assertEquals(0, reads)
    }

    @Test
    fun `an unexpected uninitialized repository is still treated as missing`() {
        assertNull(webAppRepositoryIfReady(false, false, false) { uninitialized })
    }

    @Test
    fun `normal boot returns the real repository without changing it`() {
        val repository = Any()
        var reads = 0
        val result = webAppRepositoryIfReady(false, false, false) { reads++; repository }
        assertSame(repository, result)
        assertEquals(1, reads)
    }
}
