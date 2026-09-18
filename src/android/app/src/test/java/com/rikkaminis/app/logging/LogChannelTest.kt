package com.rikkaminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogChannelTest {

    @Test
    fun `debug level routes to DEBUG channel`() {
        assertEquals(
            LogChannel.DEBUG,
            logChannelFor("[12:00:00.000] [DEBUG] [ChatScrollFollow] SSE delta"),
        )
    }

    @Test
    fun `info level routes to MAIN`() {
        assertEquals(
            LogChannel.MAIN,
            logChannelFor("[12:00:00.000] [INFO] [OpenAIProvider] REQ url=x"),
        )
    }

    @Test
    fun `warn error route to MAIN`() {
        assertEquals(LogChannel.MAIN, logChannelFor("[t] [WARN] [x] y"))
        assertEquals(LogChannel.MAIN, logChannelFor("[t] [ERROR] [x] y"))
    }

    @Test
    fun `stdout stderr channels route to MAIN`() {
        assertEquals(LogChannel.MAIN, logChannelFor("[12:00:00.000] [STDOUT] hello"))
        assertEquals(LogChannel.MAIN, logChannelFor("[12:00:00.000] [STDERR] boom"))
    }

    @Test
    fun `logcat tail lines route to MAIN`() {
        assertEquals(LogChannel.MAIN, logChannelFor("[LOGCAT] 09-18 12:00:00.000 D/Tag(1): msg"))
    }

    @Test
    fun `unparseable lines never dropped to a void - stay MAIN`() {
        assertEquals(LogChannel.MAIN, logChannelFor("no brackets at all"))
        assertEquals(LogChannel.MAIN, logChannelFor("["))
        assertEquals(LogChannel.MAIN, logChannelFor("[]"))
        assertEquals(LogChannel.MAIN, logChannelFor(""))
    }
}
