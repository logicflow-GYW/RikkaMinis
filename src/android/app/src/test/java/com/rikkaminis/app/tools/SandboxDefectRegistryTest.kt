package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [defect-registry-recall] — SandboxDefectRegistry is pure JVM, so the whole
 * parse → match → render pipeline is exercised here. The fixture mirrors the
 * real registry's A1 entry — the case that motivated the feature (a known
 * git-push defect still burned 3 probe rounds while its entry sat in memory).
 */
class SandboxDefectRegistryTest {

    private val sample = """
        # 沙箱缺陷注册表（sandbox-limits registry）

        ## A 类：路径缺陷（有健康旁路，绕行即解）

        ### A1 本地 git push / clone --local 失败
        - 症状：`unpack should have generated X / bad pack header`、`failed to create link '.l2s.tmp_obj_*': Operation not permitted`
        - 断点层：receive-pack quarantine 跨目录迁移 + 硬链接扇出
        - 健康路径：①方向翻转 `git -C <dst> fetch <src> <refspec>` ②bundle create+fetch ③`git clone --no-local`
        - 救不动：thin pack / --no-thin / protocol v0 / file:// / unpackLimit 高值 / threads=1
        - 验证：2026-10-03 三轮对照探针

        ### A2 /tmp 收割（60min sweeper）
        - 症状：活跃工作目录/文件莫名消失；"磁盘压力"归因错误
        - 健康路径：小时级工作放 `/var/minis/shared/work/`
        - 验证：2026-09-26 源码级定案

        ### A9 ICMP 阻断
        - 健康路径：curl/wget 测连通；别用 ping（挂死）
        - 验证：GLOBAL 记录
    """.trimIndent()

    // ── parse ─────────────────────────────────────────────────────────────

    @Test
    fun parse_extracts_entries_and_recovery_fields() {
        val defects = SandboxDefectRegistry.parse(sample)
        assertEquals(listOf("A1", "A2", "A9"), defects.map { it.id })
        assertEquals("本地 git push / clone --local 失败", defects[0].title)
        assertTrue(defects[0].recovery!!.contains("方向翻转"))
        assertTrue(defects[0].frozen!!.contains("thin pack"))
        assertNull(defects[1].frozen)
    }

    @Test
    fun parse_builds_seed_groups_with_union_split_and_stopword_filter() {
        val a1 = SandboxDefectRegistry.parse(sample)[0]
        assertEquals(3, a1.seedGroups.size)
        assertEquals(listOf("unpack", "generated"), a1.seedGroups[0])
        assertEquals(listOf("bad", "pack", "header"), a1.seedGroups[1])
        assertEquals(
            listOf("create", "link", "l2s.tmp_obj", "operation", "permitted"),
            a1.seedGroups[2],
        )
    }

    @Test
    fun entries_without_backticked_seeds_have_empty_groups() {
        val defects = SandboxDefectRegistry.parse(sample)
        assertTrue(defects[1].seedGroups.isEmpty()) // A2: descriptive symptom, no backticks
        assertTrue(defects[2].seedGroups.isEmpty()) // A9: no symptom line at all
    }

    @Test
    fun stopword_only_seeds_are_skipped() {
        val text = "### X1 t\n- 症状：`fatal error`\n- 健康路径：p\n"
        val d = SandboxDefectRegistry.parse(text).single()
        assertTrue(d.seedGroups.isEmpty())
    }

    // ── 2026-10-27: heading bleed, field forms, generic-only seeds ────────

    @Test
    fun parse_accepts_double_hash_entry_heading() {
        val text = """
            ### B5 陈旧视图类
            - 通用判据：换直接读取原语交叉验证
            ## S28 壳内 scan.sh 整链假红
            - 症状：`python3: Function not implemented`
            - 健康路径：逐门直跑
        """.trimIndent()
        val d = SandboxDefectRegistry.parse(text)
        assertEquals(listOf("B5", "S28"), d.map { it.id })
        assertTrue(d[0].seedGroups.isEmpty())
        assertEquals(listOf("python3", "function", "implemented"), d[1].seedGroups.single())
    }

    @Test
    fun non_entry_heading_does_not_bleed_into_previous_entry() {
        // The live defect: a `## <non-entry>` section's symptom line used to be
        // appended to the last `###` entry (B5 fired carrying A19's seeds).
        val text = """
            ### B5 陈旧视图类
            - 通用判据：x
            ## 元规则
            - 症状：`cat: /tmp/.native-offload-`
            - 健康路径：错开启动
        """.trimIndent()
        val d = SandboxDefectRegistry.parse(text).single()
        assertEquals("B5", d.id)
        assertTrue(d.seedGroups.isEmpty())
        assertNull(d.recovery)
    }

    @Test
    fun parse_accepts_bold_field_lines() {
        // Nine registry entries write `- **症状**：`; the old strict prefix
        // check made every one of them permanently unmatchable.
        val text = """
            ### S26 git clone 后坏对象
            - **症状**：`invalid sha1 pointer`
            - **健康路径**：删三个断链 symlink
        """.trimIndent()
        val d = SandboxDefectRegistry.parse(text).single()
        assertEquals(listOf("invalid", "sha1", "pointer"), d.seedGroups.single())
        assertTrue(d.recovery!!.contains("断链"))
    }

    @Test
    fun generic_only_seed_groups_never_become_match_groups() {
        val text = """
            ### X9 t
            - 症状：`grep -n X file` / `" CODE "` / `wait` / `bad pack`
            - 健康路径：p
        """.trimIndent()
        val d = SandboxDefectRegistry.parse(text).single()
        assertTrue("all-generic seeds must be dropped", d.seedGroups.isEmpty())
    }

    // ── match ─────────────────────────────────────────────────────────────

    @Test
    fun match_hits_via_second_union_alternative() {
        val defects = SandboxDefectRegistry.parse(sample)
        val hits = SandboxDefectRegistry.match("fatal: bad pack header", defects)
        assertEquals(listOf("A1"), hits.map { it.id })
    }

    @Test
    fun match_hits_via_quarantine_link_error_with_random_suffix() {
        val defects = SandboxDefectRegistry.parse(sample)
        val out = "error: failed to create link '.l2s.tmp_obj_9f3ac1': Operation not permitted"
        val hits = SandboxDefectRegistry.match(out, defects)
        assertEquals(listOf("A1"), hits.map { it.id })
    }

    @Test
    fun match_stays_conservative_on_bare_eperm() {
        val defects = SandboxDefectRegistry.parse(sample)
        // EPERM alone lacks create/link/l2s… tokens — must NOT hit A1.
        val hits = SandboxDefectRegistry.match(
            "rm: cannot remove '/etc/hosts': Operation not permitted", defects,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun match_requires_the_specific_name_token_too() {
        val defects = SandboxDefectRegistry.parse(sample)
        // Has create+link+operation+permitted but a different tmp name → no hit.
        val out = "error: failed to create link '/tmp/other_thing_77': Operation not permitted"
        assertTrue(SandboxDefectRegistry.match(out, defects).isEmpty())
    }

    @Test
    fun descriptive_entries_never_auto_match() {
        val defects = SandboxDefectRegistry.parse(sample)
        val hits = SandboxDefectRegistry.match("活跃工作目录莫名消失，磁盘压力归因错误", defects)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun no_hit_on_shell_noise_that_used_to_fire_a1b() {
        // Replay of a real 2026-10-27 failing read: an unquoted $LOG made grep
        // split the filename, so the output was 7× "No such file or directory"
        // plus the exit-code trailer. It used to recall A1b (裸 NUL) because its
        // seed `grep -n X file` reduced to [grep, file] and `" CODE "` to [code].
        val defects = SandboxDefectRegistry.parse(
            "### A1b t\n- 症状：`binary file matches`\n- 健康路径：把裸 NUL 规范成转义\n",
        )
        val noise = """
            LOG=build/10_Verify JNI symbols match package.txt  size=1929
            grep: build/10_Verify: No such file or directory
            grep: JNI: No such file or directory
            grep: symbols: No such file or directory
            (exit code: 1) (exit code 1)
        """.trimIndent()
        assertTrue(SandboxDefectRegistry.match(noise, defects).isEmpty())
        // positive control: the specific seed of the same entry still fires
        assertEquals(
            1,
            SandboxDefectRegistry.match("MarkdownClipboard.kt: binary file matches", defects).size,
        )
    }

    @Test
    fun no_hit_on_stall_guard_text_that_used_to_fire_b5() {
        // `wait` as a whole seed group matched the stall-guard wording; the
        // group is now dropped, while A19's real error line still recalls.
        val defects = SandboxDefectRegistry.parse(
            "### A19 t\n- 症状：`cat: /tmp/.native-offload-`\n- 健康路径：错开启动\n",
        )
        val stall = "[stall-guard] no output and no CPU progress for 180s — the command " +
            "was interrupted and its process tree was killed. If it was waiting for input, " +
            "pass explicit arguments/flags"
        assertTrue(SandboxDefectRegistry.match(stall, defects).isEmpty())
        assertEquals(
            1,
            SandboxDefectRegistry.match(
                "cat: /tmp/.native-offload-29831-4: No such file or directory", defects,
            ).size,
        )
    }

    @Test
    fun cjk_seed_tokens_are_supported() {
        val text = "### X2 t\n- 症状：`上传阶段失败`\n- 健康路径：重试\n"
        val hits = SandboxDefectRegistry.match(
            "20:53 NetworkError 上传阶段失败", SandboxDefectRegistry.parse(text),
        )
        assertEquals(1, hits.size)
    }

    @Test
    fun match_caps_hints_at_max() {
        val text = """
            ### X1 one
            - 症状：`shared marker phrase`
            - 健康路径：p1
            ### X2 two
            - 症状：`shared marker phrase`
            - 健康路径：p2
            ### X3 three
            - 症状：`shared marker phrase`
            - 健康路径：p3
        """.trimIndent()
        val hits = SandboxDefectRegistry.match(
            "boom: shared marker phrase", SandboxDefectRegistry.parse(text),
        )
        assertEquals(2, hits.size) // literal cap, not derived (JVM 装置自指纪律)
    }

    // ── hintSuffix (render) ───────────────────────────────────────────────

    @Test
    fun hint_suffix_null_for_absent_or_unmatched_registry() {
        assertNull(SandboxDefectRegistry.hintSuffix("fatal: bad pack header", null))
        assertNull(SandboxDefectRegistry.hintSuffix("fatal: bad pack header", ""))
        assertNull(SandboxDefectRegistry.hintSuffix("grep: no such file or directory", sample))
    }

    @Test
    fun hint_suffix_renders_id_title_path_and_registry_pointer() {
        val s = SandboxDefectRegistry.hintSuffix(
            "error: failed to create link '.l2s.tmp_obj_abc': Operation not permitted",
            sample,
        )
        assertNotNull(s)
        val h = s!!
        assertTrue(h.contains("A1"))
        assertTrue(h.contains("本地 git push"))
        assertTrue(h.contains("健康路径："))
        assertTrue(h.contains("救不动"))
        assertTrue(h.contains("sandbox-limits.md"))
    }

    // ── wiring: the executeTool failure path consumes the hint ───────────
    //
    // ChatViewModel cannot be instantiated on the JVM, so — like
    // FastioWiringTest / CompactQuietFirstTest — the wiring is pinned on the
    // source text itself. Negative control: the same probe must fail once
    // the append call is removed, or the pin checks nothing.

    @Test
    fun wiring_failure_path_appends_registry_hint() {
        val src = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt",
        )
        assertHintWiring(src)
        val mutated = src.replace(
            "val defectHint = defectRegistryHintFor(result.output)", "",
        )
        var caught = false
        try {
            assertHintWiring(mutated)
        } catch (_: AssertionError) {
            caught = true
        }
        assertTrue("negative control: probe still passes on mutated source", caught)
    }

    private fun assertHintWiring(src: String) {
        val atGuard = src.indexOf("if (!result.success)")
        val atCall = src.indexOf("val defectHint = defectRegistryHintFor(result.output)")
        val atCopy = src.indexOf("return result.copy(output = result.output + defectHint)")
        val atRegistryPath = src.indexOf("\"minis-global/shared/sandbox-limits.md\"")
        assertTrue("the failure guard must exist", atGuard >= 0)
        assertTrue("the failure path must call defectRegistryHintFor", atCall >= 0)
        assertTrue("the hinted result must be returned", atCopy >= 0)
        assertTrue("the registry must be read from minis-global/shared", atRegistryPath >= 0)
        assertTrue("the hint must be wired inside the failure guard", atGuard < atCall)
        assertTrue("the call must precede the returned copy", atCall < atCopy)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; the sandbox JVM
     * run can start anywhere under the repo — no fixed depth works.
     */
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
}
