package com.rikkaminis.app.ui.sandbox

import androidx.lifecycle.ViewModelStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real ViewModel. Every read is released explicitly, never by wall-clock timing. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserLoadOwnershipTest {
    private val main = StandardTestDispatcher()
    private val io = StandardTestDispatcher(main.scheduler)
    private val store = ViewModelStore()
    private val reads = ArrayList<Read>()
    private lateinit var root: File
    private lateinit var vm: FileBrowserViewModel

    private class Read(val path: File, val hidden: Boolean, val continuation: Continuation<List<FileItem>>) {
        var completed = false
        fun succeed(items: List<FileItem>) {
            check(!completed)
            completed = true
            continuation.resume(items)
        }
        fun fail(error: Exception = IOException("listing failed")) {
            check(!completed)
            completed = true
            continuation.resumeWithException(error)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        root = Files.createTempDirectory("browser-load-owner").toFile()
        vm = FileBrowserViewModel(
            rootPath = root,
            listingDispatcher = io,
            fileOperationDispatcher = io,
            directoryReader = { path, hidden ->
                // Deliberately NON-cancellable, like native stat/listFiles.
                suspendCoroutine { reads += Read(path, hidden, it) }
            },
        )
        store.put("browser", vm)
        drain()
        assertEquals(1, reads.size)
    }

    @After
    fun tearDown() {
        store.clear()
        reads.filterNot { it.completed }.forEach { it.succeed(emptyList()) }
        drain()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun drain() = main.scheduler.advanceUntilIdle()
    private fun item(name: String, directory: Boolean = false): FileItem = FileItem(
        file = File(root, name), name = name, isDirectory = directory, isSymlink = false, size = 1L,
    )
    private fun names(): List<String> = vm.uiState.value.items.map { it.name }
    private fun enterAThenB() {
        vm.navigateTo(item("A", directory = true))
        drain()
        assertTrue(vm.goBack())
        drain()
        assertEquals(3, reads.size)
        assertEquals(File(root, "A"), reads[1].path)
        assertEquals(root, reads[2].path)
    }
    private fun assertB() {
        assertEquals(root.absolutePath, vm.uiState.value.currentPath)
        assertEquals(listOf(root.name), vm.uiState.value.pathComponents)
        assertEquals(listOf("B.txt"), names())
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isEmpty)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun slowASuccessCannotOverwriteBOrItsSortCache() {
        reads[0].succeed(emptyList())
        drain()
        enterAThenB()
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        reads[1].succeed(listOf(item("A.txt")))
        drain()
        assertB()
        vm.setSort(ascending = false)
        assertB()
    }

    @Test
    fun staleSuccessCannotFinishTheNewRequestsSpinner() {
        reads[0].succeed(emptyList())
        drain()
        enterAThenB()
        reads[1].succeed(listOf(item("A.txt")))
        drain()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(names().isEmpty())
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        assertB()
    }

    @Test
    fun staleFailureCannotClearBOrReportAnError() {
        reads[0].succeed(emptyList())
        drain()
        enterAThenB()
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        reads[1].fail()
        drain()
        assertB()
        vm.setSort(key = FileSortKey.SIZE)
        assertB()
    }

    @Test
    fun staleFailureCannotFinishTheNewRequestsSpinner() {
        reads[0].succeed(emptyList())
        drain()
        enterAThenB()
        reads[1].fail()
        drain()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(names().isEmpty())
        assertNull(vm.uiState.value.errorMessage)
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        assertB()
    }

    @Test
    fun samePathRefreshStillRejectsThePreviousGeneration() {
        vm.navigateToPathComponent(0)
        drain()
        assertEquals(reads[0].path, reads[1].path)
        reads[1].succeed(listOf(item("B.txt")))
        drain()
        reads[0].succeed(listOf(item("old.txt")))
        drain()
        assertB()
    }

    @Test
    fun hiddenToggleRoundTripCannotReviveAnOlderIdenticalPreference() {
        vm.setShowHidden(true)
        drain()
        vm.setShowHidden(false)
        drain()
        assertEquals(listOf(false, true, false), reads.map { it.hidden })
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        reads[1].succeed(listOf(item(".secret")))
        drain()
        reads[0].succeed(listOf(item("old.txt")))
        drain()
        assertFalse(vm.uiState.value.showHidden)
        assertB()
    }

    @Test
    fun sortDuringReplacementCannotReviveOldItemsAndUsesLatestOptions() {
        reads[0].succeed(listOf(item("old.txt")))
        drain()
        vm.navigateTo(item("A", directory = true))
        drain()
        vm.setSort(ascending = false)
        assertTrue(names().isEmpty())
        assertTrue(vm.uiState.value.isLoading)
        reads[1].succeed(listOf(item("a.txt"), item("z.txt")))
        drain()
        assertEquals(listOf("z.txt", "a.txt"), names())
        vm.navigateToPathComponent(0)
        drain()
        reads[2].succeed(listOf(item("b.txt"), item("y.txt")))
        drain()
        assertEquals(root.absolutePath, vm.uiState.value.currentPath)
        assertEquals(listOf("y.txt", "b.txt"), names())
        assertFalse(vm.uiState.value.sortAscending)
    }

    @Test
    fun currentFailureClearsTheCacheAndNextSuccessClearsTheError() {
        reads[0].succeed(listOf(item("old.txt")))
        drain()
        vm.navigateToPathComponent(0)
        drain()
        reads[1].fail()
        drain()
        assertEquals("listing failed", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.isEmpty)
        vm.setSort(ascending = false)
        assertTrue(names().isEmpty())
        vm.navigateToPathComponent(0)
        drain()
        assertNull(vm.uiState.value.errorMessage)
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        assertB()
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(): MutableStateFlow<FileBrowserUiState> {
        val field = FileBrowserViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        return field.get(vm) as MutableStateFlow<FileBrowserUiState>
    }

    @Test
    fun commitRequiresTheDisplayedHostPathEvenWithoutAnotherGeneration() {
        val state = stateFlow()
        val elsewhere = File(root, "elsewhere").absolutePath
        state.value = state.value.copy(currentPath = elsewhere)
        reads[0].succeed(listOf(item("wrong.txt")))
        drain()
        assertEquals(elsewhere, vm.uiState.value.currentPath)
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(names().isEmpty())
        vm.setSort()
        assertTrue(names().isEmpty())
    }

    @Test
    fun commitRequiresLogicalPathEvenWhenTheHostMappingIsUnchanged() {
        val field = FileBrowserViewModel::class.java.getDeclaredField("relativePath")
        field.isAccessible = true
        field.set(vm, "alias")
        reads[0].succeed(listOf(item("wrong.txt")))
        drain()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(names().isEmpty())
    }

    @Test
    fun commitRequiresTheHiddenPreferenceSnapshot() {
        val state = stateFlow()
        state.value = state.value.copy(showHidden = true)
        reads[0].succeed(listOf(item("wrong.txt")))
        drain()
        assertTrue(vm.uiState.value.showHidden)
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(names().isEmpty())
    }

    @Test
    fun productionReaderStillListsAndFiltersRealFiles() {
        File(root, "visible.txt").writeText("visible")
        File(root, ".hidden.txt").writeText("hidden")
        val browser = FileBrowserViewModel(rootPath = root, listingDispatcher = io)
        store.put("production-reader", browser)
        drain()
        assertEquals(listOf("visible.txt"), browser.uiState.value.items.map { it.name })
        browser.setShowHidden(true)
        drain()
        assertEquals(listOf(".hidden.txt", "visible.txt"), browser.uiState.value.items.map { it.name })
        assertFalse(browser.uiState.value.isLoading)
        assertNull(browser.uiState.value.errorMessage)
    }

    @Test
    fun cancelledReadIsNotRenderedAsADirectoryError() {
        reads[0].fail(CancellationException("cancelled"))
        drain()
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(names().isEmpty())
    }

    @Test
    fun postDeleteRefreshClaimsOnMainAndRejectsThePreDeleteGeneration() {
        val victim = File(root, "victim.txt").apply { writeText("victim") }
        val victimItem = FileItem.from(victim)!!
        reads[0].succeed(listOf(victimItem))
        drain()
        vm.navigateToPathComponent(0)
        drain()
        vm.deleteItem(victimItem)
        drain()
        assertFalse(victim.exists())
        assertEquals(3, reads.size)
        assertEquals(root, reads[2].path)
        reads[2].succeed(listOf(item("B.txt")))
        drain()
        reads[1].succeed(listOf(victimItem))
        drain()
        assertB()
        vm.setSort()
        assertB()
    }
}
