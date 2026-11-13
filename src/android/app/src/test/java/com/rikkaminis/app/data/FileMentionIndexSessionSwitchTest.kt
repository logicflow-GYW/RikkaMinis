package com.rikkaminis.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [R8-1114 A-F03] Switching sessions clears the old @-mention table before
 * the replacement scan publishes its first batch.
 */
class FileMentionIndexSessionSwitchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sessionSwitchDoesNotExposePreviousSessionEntries() {
        val root = Files.createTempDirectory("fmi-session-switch").toFile()
        try {
            val first = File(root, "minis-sessions/first/workspace").apply { mkdirs() }
            File(first, "old.txt").writeText("old")
            val second = File(root, "minis-sessions/second/workspace").apply { mkdirs() }
            File(second, "new.txt").writeText("new")
            val index = FileMentionIndex(
                filesDir = root,
                mountsProvider = { emptyList() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )

            index.refreshIfNeeded("first")
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(index.entries.value.any { it.linuxPath.endsWith("old.txt") })

            index.refreshIfNeeded("second")
            assertFalse(
                "the old session must be removed before the second scan publishes",
                index.entries.value.any { it.linuxPath.endsWith("old.txt") },
            )
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(index.entries.value.any { it.linuxPath.endsWith("new.txt") })
        } finally {
            root.deleteRecursively()
        }
    }
}
