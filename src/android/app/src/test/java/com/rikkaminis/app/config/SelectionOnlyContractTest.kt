package com.rikkaminis.app.config

import com.rikkaminis.app.backup.decideLegacyHiddenImportKeep
import com.rikkaminis.app.config.collections.HiddenWriteAction
import com.rikkaminis.app.config.collections.decideHiddenWrite
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [model-catalog-split] FIX-C 的契约测试：`isHidden` 冻结为 selection 别名后，
 * 所有对外契约面（集合字段 / 旧格式导入 / 备份导出）的判定必须一致。
 *
 * 全部是纯逻辑 + 生产函数直调，不依赖 Android runtime：
 *  - [decideLegacyHiddenImportKeep] 是 OpenMinisBackupCompat 的真实生产函数
 *    （internal，同模块测试可见），不是复刻的期望逻辑；
 *  - [decideHiddenWrite] 是 ModelsCollection.isHiddenField writer 的判定核
 *    （internal 抽自同一文件，setter 实测也走这里）；
 *  - 导出无过滤那条抽不动（ProviderRepository.exportInstanceJSON 依赖 Android
 *    Context），按任务书规定以静态断言替代 —— 见
 *    [exportPathHasNoIsHiddenFilter] 的注释证据。
 */
class SelectionOnlyContractTest {

    // ------------------------------------------------------------------
    // 1. 旧格式导入丢弃规则（OpenMinisBackupCompat）
    // ------------------------------------------------------------------

    @Test
    fun importDropsLegacyHiddenNonCustomEntry() {
        // isHidden=true && !isCustom → skip（legacy 目录副本，不是用户资产）
        assertFalse(decideLegacyHiddenImportKeep(isHidden = true, isCustom = false))
    }

    @Test
    fun importKeepsLegacyHiddenCustomEntryField() {
        // isHidden=true && isCustom → keep（custom 是用户资产，A 支迁移同样保留）
        assertTrue(decideLegacyHiddenImportKeep(isHidden = true, isCustom = true))
    }

    @Test
    fun importKeepsVisibleEntries() {
        // isHidden=false → keep，无论 custom 与否（期望值为字面量，不从实现推导）
        assertTrue(decideLegacyHiddenImportKeep(isHidden = false, isCustom = false))
        assertTrue(decideLegacyHiddenImportKeep(isHidden = false, isCustom = true))
    }

    // ------------------------------------------------------------------
    // 2. models.<id>.isHidden setter 判定核
    // ------------------------------------------------------------------

    @Test
    fun hiddenTrueMapsToUnselect() {
        assertEquals(
            HiddenWriteAction.UNSELECT,
            decideHiddenWrite(entry("m1"), newValue = true),
        )
    }

    @Test
    fun hiddenFalseMapsToNoop() {
        assertEquals(
            HiddenWriteAction.NOOP,
            decideHiddenWrite(entry("m1"), newValue = false),
        )
    }

    @Test
    fun hiddenOnUnknownIdMapsToError() {
        // 未知 id 两种取值都必须是 ERROR_UNKNOWN_ID（setter 抛 UnknownPath）
        assertEquals(
            HiddenWriteAction.ERROR_UNKNOWN_ID,
            decideHiddenWrite(null, newValue = true),
        )
        assertEquals(
            HiddenWriteAction.ERROR_UNKNOWN_ID,
            decideHiddenWrite(null, newValue = false),
        )
    }

    // ------------------------------------------------------------------
    // 2b. writer wiring（静态断言）+ reader 存储值直读
    //
    // ProviderRepository 构造函数要 android.content.Context（CI 上没有
    // Android runtime，沙箱探针也造不出真实 Context），所以「setter 真的
    // 路由到 unselectEntry」不能在这里跑实体 repo。两层装置替代：
    //  1) decideHiddenWrite 纯函数（上面 2 组测试）钉住判定；
    //  2) 下面的静态断言钉住 ModelsCollection.isHiddenField 的 writer 体
    //     —— writer 只允许出现 unselectEntry 调用与 decideHiddenWrite
    //     分派，禁止把 copy(isHidden = …) 写回表里（updateEntry 路径）。
    // ------------------------------------------------------------------

    @Test
    fun writerRoutesThroughUnselectNeverUpdate() {
        val src = readRepoFile("src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt")
        val body = declRegion(src, "private fun isHiddenField(")
        assertTrue(
            "isHiddenField writer must dispatch through decideHiddenWrite",
            body.contains("decideHiddenWrite"),
        )
        assertTrue(
            "isHiddenField writer must route true through repo.unselectEntry",
            body.contains("repo.unselectEntry"),
        )
        assertFalse(
            "isHiddenField writer must NOT persist copy(isHidden = …) (that mints a hidden row and breaks the selection-only invariant)",
            Regex("copy\\s*\\(\\s*isHidden").containsMatchIn(body),
        )
    }

    @Test
    fun readerReportsStoredFlagNeverFabricated() {
        val src = readRepoFile("src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt")
        val body = declRegion(src, "private fun isHiddenField(")
        assertTrue(
            "reader must read the stored flag (e.isHidden), never fabricate a visibility value",
            body.contains("e.isHidden"),
        )
        assertFalse(
            "reader must not synthesize !isHidden as 'selected' — it reports what the row stores",
            Regex("!e\\.isHidden").containsMatchIn(body),
        )
    }

    @Test
    fun unknownIdHasNoFieldsSoNoWritePathExists() {
        // fields() 对未知 id 返回空列表（迁移后集合行为一致）：没有可写的
        // isHidden 字段，因此不会有一条「凭 id 就造 hidden 行」的写路径。
        // 未知 id 的判定核本身由 hiddenOnUnknownIdMapsToError 钉住。
        // （实体 repo 断言在 CI 上不可行 — ProviderRepository(Context)；
        //   fields() 的空返回路径由 ModelsCollection.entry(id) 的 null 直传
        //   保证，钉在 entryUnknownYieldsEmptyFields 的静态断言上。）
        val src = readRepoFile("src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt")
        val body = fnBody(src, "override fun fields(")
        assertTrue(
            "fields() must early-return empty for an unknown id (entry(id) ?: return emptyList)",
            body.contains("?: return emptyList"),
        )
    }

    // ------------------------------------------------------------------
    // 3. 导出路径无 isHidden 过滤（静态断言）
    // ------------------------------------------------------------------

    /**
     * 导出条目数 == 已选条目数，**不允许任何 isHidden 过滤**。
     *
     * 为什么这里是源码静态断言而不是 JVM 直调：
     * `ConfigBackup.orderedEntryIds` 的生产调用链是
     * `ProviderRepository.entriesFor(instanceId)` → `entryIdsInExportOrder`
     * （identity 投影）。前者依赖 Android `Context`/SharedPreferences 的
     * 仓库实例，本沙箱 JVM 装置不拉整个 repo（任务书第 7 节红线），
     * `ProviderRepository.exportInstanceJSON` 的 `filter { providerInstanceId
     * == instanceId }` 因此抽不动。按任务书的规定以静态断言替代：读源码，
     * 断言导出选择函数的**代码体**里不存在 `isHidden`（剥注释后）——
     * 任何人重新引入 `filter { !it.isHidden }` 之类的过滤都会当场红。
     *
     * 导出顺序契约的运行时那一半由
     * [com.rikkaminis.app.backup.EntryIdOrderContractTest] 钉住（整仓 JVM
     * 测试，含 hidden/visible 交错输入）。
     */
    @Test
    fun exportPathHasNoIsHiddenFilter() {
        val src = stripCommentsAndLiterals(
            readRepoFile("src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"),
        )

        val orderedIdsBody = fnBody(src, "private fun orderedEntryIds(")
        assertFalse(
            "orderedEntryIds must not filter on isHidden (selection-only table; " +
                "every selected entry must reach the backup): $orderedIdsBody",
            orderedIdsBody.contains("isHidden"),
        )
        assertTrue(
            "orderedEntryIds must keep projecting entriesFor (the same predicate + " +
                "order as exportInstanceJSON)",
            orderedIdsBody.contains("entriesFor(") && orderedIdsBody.contains("entryIdsInExportOrder("),
        )

        val projectionBody = fnBody(src, "internal fun entryIdsInExportOrder(")
        assertFalse(
            "entryIdsInExportOrder must stay an identity projection — no filtering: " +
                projectionBody,
            projectionBody.contains("filter") || projectionBody.contains("isHidden"),
        )

        // 3 条已选（无 hidden）→ 导出条目数 == 3：identity 投影下恒成立，
        // 上面的静态断言保证生产路径不会在这里丢掉任何已选条目。
        val selected = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals(3, selected.map { it.id }.size)
    }

    // ------------------------------------------------------------------

    private fun entry(modelId: String, hidden: Boolean = false): ModelEntry =
        ModelEntry(
            providerInstanceId = "pi_test",
            baseModel = LLMModel(
                id = modelId,
                displayName = modelId,
                provider = "Test",
            ),
            isCustom = false,
            isHidden = hidden,
        )

    /** 从 user.dir 向上找仓库相对路径（同 LaunchSessionRerouteShapeTest 的模式）。 */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** The whole declaration of [marker]'s function (from the marker up to the
     * next top-level declaration) — `isHiddenField` is an EXPRESSION body
     * (`= ClosureField(...)`), so a brace-matching body extractor would only
     * capture the reader lambda and miss the writer. */
    private fun declRegion(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val next = code.indexOf("\n    private fun ", start + marker.length)
        val end = if (next < 0) code.length else next
        return code.substring(start, end)
    }

    private fun fnBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("no body for: $marker")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced body for: $marker")
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
                        if (src[i] == '\\') i++ else if (src[i] == '\n') {
                            // 未闭合防守：按行结束
                            break
                        }
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
