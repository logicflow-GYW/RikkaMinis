package com.rikkaminis.app.config.collections

import android.content.Context
import android.content.ContextWrapper
import com.rikkaminis.app.config.ConfigValue
import com.rikkaminis.app.config.FakeSharedPreferences
import com.rikkaminis.app.data.repository.EnvVarRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [R8-1114 A-F02] A metadata-only edit (note OR group) must never manufacture an
 * empty encrypted value when the value store has no readable value for the key.
 */
class EnvVarCollectionNoteWriteTest {

    private val filesDir = Files.createTempDirectory("envvar-note").toFile()

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private class StubContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    @Test
    fun noteWriteWithMetadataOnlyEntryDoesNotCreateEmptySecret() {
        File(filesDir, "env-vars.json").writeText(
            "[{\"id\":\"entry-1\",\"key\":\"FOO\",\"note\":\"old\",\"group\":\"ops\",\"createdAt\":1}]",
        )
        val prefs = FakeSharedPreferences()
        val repo = EnvVarRepository.withPrefs(StubContext(filesDir), prefs)
        val note = EnvVarsCollection(repo).fields("FOO").single { it.path.endsWith(".note") }

        note.write(ConfigValue.Str("rotated later"))

        assertNull("the absent secret must remain absent", repo.getValue("FOO"))
        assertFalse("note write must not synthesize an empty encrypted value", prefs.map.containsKey("FOO"))
        assertEquals("rotated later", repo.entries.value.single().note)
        assertEquals("ops", repo.entries.value.single().group)
    }

    // [R8-1114 A-F02] The GROUP writer is the same family: it read the value
    // with `?: ""` and wrote it back through update(), so changing a group on a
    // metadata-only entry created an empty secret. One write path fixed and the
    // other left alone is the classic "fixed one of |S|".
    @Test
    fun groupWriteWithMetadataOnlyEntryDoesNotCreateEmptySecret() {
        File(filesDir, "env-vars.json").writeText(
            "[{\"id\":\"entry-2\",\"key\":\"BAR\",\"note\":\"keep me\",\"group\":\"\",\"createdAt\":1}]",
        )
        val prefs = FakeSharedPreferences()
        val repo = EnvVarRepository.withPrefs(StubContext(filesDir), prefs)
        val group = EnvVarsCollection(repo).fields("BAR").single { it.path.endsWith(".group") }

        group.write(ConfigValue.Str("cloudflare"))

        assertNull("the absent secret must remain absent", repo.getValue("BAR"))
        assertFalse("group write must not synthesize an empty encrypted value", prefs.map.containsKey("BAR"))
        assertEquals("cloudflare", repo.entries.value.single().group)
        assertEquals("the note must ride through untouched", "keep me", repo.entries.value.single().note)
    }
}
