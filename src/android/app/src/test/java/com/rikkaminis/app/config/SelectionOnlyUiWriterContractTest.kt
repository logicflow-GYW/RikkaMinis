package com.rikkaminis.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [selection-only-ui-writer-1112] UI 写路径的 selection-only 契约。
 *
 * 背景：分表后 `config.modelEntries` 只存「已选」条目——没有 `isHidden = true`
 * 的行存在（一次性迁移会删掉，备份导入也会丢弃）。但详情页
 * `ModelEntryDetailScreen` 仍保留着旧的「Visibility」开关：它把 `isHidden`
 * 存进本地 state，并在 Save 时 `copy(isHidden = …)` 写回。
 *
 * 后果（本次审查实证）：该行对所有 `!isHidden` 读者（模型选择器、onboarding、
 * 分组页、agent-loop 页）不可见，而 Manage sheet 读全表仍显示它「已选」；
 * 下次备份恢复时导入器按 `decideLegacyHiddenImportKeep` 把它丢掉 —— 用户
 * 已选的模型静默消失。
 *
 * 与 [SelectionOnlyContractTest] 同族：那边守 config setter 与旧格式导入，
 * 这边守 UI 写路径。两者合起来才是「没有任何写路径能铸出 hidden 行」。
 *
 * 断言方式：静态源码断言（UI 层逻辑不可抽为纯函数，理由同
 * SelectionOnlyContractTest 的 exportPathHasNoIsHiddenFilter）——但断言前先
 * 剥掉注释与字符串字面量，保证注释里的 `isHidden` 文字不会让断言假绿。
 */
class SelectionOnlyUiWriterContractTest {

    private val screenRel =
        "src/main/java/com/rikkaminis/app/ui/settings/ModelEntryDetailScreen.kt"

    // ------------------------------------------------------------------
    // 1. 任何形态的 copy(isHidden = …) 都不得存在
    // ------------------------------------------------------------------

    @Test
    fun screenNeverMintsAHiddenRow() {
        val src = stripCommentsAndLiterals(readRepoFile(screenRel))

        // 期望值写死字面量，不从实现常量推导 —— 装置不得自指。
        val minting = Regex("copy\\s*\\(\\s*isHidden\\s*=").find(src)
        assertTrue(
            "the detail screen must never call copy(isHidden = …): it would mint a row " +
                "the one-shot migration deletes and the backup importer drops, so the " +
                "user's selected model would silently vanish on the next restore. " +
                "Found: ${minting?.value}",
            minting == null,
        )
    }

    // ------------------------------------------------------------------
    // 2. Save 路径不得触碰 isHidden
    // ------------------------------------------------------------------

    @Test
    fun savePathDoesNotPersistIsHidden() {
        val src = stripCommentsAndLiterals(readRepoFile(screenRel))
        // 取所有 updateEntry 调用行 —— Save 路径就是这一处写入点。
        val saveLines = src.lines().filter { it.contains("updateEntry(") }
        assertTrue(
            "the save path must not persist isHidden — the persisted value is the " +
                "selection itself, not a visibility flag. Lines: $saveLines",
            saveLines.none { it.contains("isHidden") },
        )
        // 兜底：updateEntry 调用前后的窗口里也不该有 isHidden
        val window = src.lines().filter {
            it.contains("updateEntry(") || it.contains("entry.copy(")
        }
        assertFalse(
            "the save window must not mention isHidden. Lines: $window",
            window.any { it.contains("isHidden") },
        )
    }

    // ------------------------------------------------------------------
    // 3. 「从选择中移除」必须走 unselectEntry
    // ------------------------------------------------------------------

    @Test
    fun removalGoesThroughUnselectEntry() {
        val src = stripCommentsAndLiterals(readRepoFile(screenRel))
        assertTrue(
            "the detail screen must expose 'remove from selection' through " +
                "unselectEntry — the only membership operation left in a " +
                "selection-only table. Without it the user loses the only surface " +
                "for leaving the selection set from this screen.",
            src.contains("unselectEntry"),
        )
    }

    // ------------------------------------------------------------------
    // 4. 详情页不再持有 isHidden 的本地 state
    // ------------------------------------------------------------------

    @Test
    fun screenKeepsNoLocalIsHiddenState() {
        val src = stripCommentsAndLiterals(readRepoFile(screenRel))
        // `var isHidden by remember(...)` 是旧开关的 state 载体。
        val localState = Regex("var\\s+isHidden\\s+by\\s+remember").find(src)
        assertTrue(
            "the detail screen must not keep a local `isHidden` state — there is no " +
                "visibility toggle left in a selection-only table. Found: ${localState?.value}",
            localState == null,
        )
    }

    // ------------------------------------------------------------------
    // helpers（与 SelectionOnlyContractTest 同模式，独立实现避免耦合）
    // ------------------------------------------------------------------

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? =
            java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** 剥掉注释与字符串/字符字面量，保留其余原样（Kotlin 近似，够用）。 */
    private fun stripCommentsAndLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    i += 3
                    while (i + 2 < n && !(src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"')) i++
                    i += 3
                }
                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') i++ else if (src[i] == '\n') break
                        i++
                    }
                    i++
                }
                c == '\'' -> {
                    i++
                    while (i < n && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
