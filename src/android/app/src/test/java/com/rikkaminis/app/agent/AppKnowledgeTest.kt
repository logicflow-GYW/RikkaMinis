package com.rikkaminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [knowledge-pack] Unit tests for the Android-free half of `app_guide`:
 * frontmatter parsing, pack parsing and index lookup.
 *
 * These run against the REAL production source (`agent/AppKnowledge.kt` has
 * no android.* imports on purpose), so a passing suite means the lookup the
 * tool performs at runtime is the one exercised here — not a re-implementation.
 */
class AppKnowledgeTest {

    private val wechat = """
        ---
        name: wechat
        display: 微信
        description: 腾讯即时通讯 App
        packages: ["com.tencent.mm"]
        aliases: ["WeChat", "wechat", "weixin", "微信"]
        ---

        ## 页面结构
        - 底部四个标签。
        ## 常用入口
        - 设置：我 → 设置。
        ## 易混点
        - 订阅号消息。
        ## 登录与安全页
        - 账号与安全。
    """.trimIndent()

    private val alipay = """
        ---
        name: alipay
        display: 支付宝
        packages: ["com.eg.android.AlipayGphone"]
        aliases: ["Alipay", "支付宝"]
        ---

        ## 页面结构
        - 底部标签。
    """.trimIndent()

    private val index by lazy {
        AppKnowledgeIndex(
            listOfNotNull(
                parseAppKnowledgePack("wechat", wechat),
                parseAppKnowledgePack("alipay", alipay),
            )
        )
    }

    // 1. Normal frontmatter: keys land in fields, body excludes the header.
    @Test
    fun parsesFrontmatterFieldsAndBody() {
        val doc = parseFrontmatter(wechat)
        assertNotNull(doc)
        val fields = requireNotNull(doc).fields
        assertEquals("微信", fields["display"])
        assertEquals("[\"com.tencent.mm\"]", fields["packages"])
        assertEquals("[\"WeChat\", \"wechat\", \"weixin\", \"微信\"]", fields["aliases"])
        assertTrue(doc.body.startsWith("## 页面结构"))
        assertTrue("body must not carry the closing fence", !doc.body.contains("---"))
    }

    // 2. No frontmatter → null, and never an exception.
    @Test
    fun missingFrontmatterReturnsNull() {
        assertNull(parseFrontmatter("# 微信\n没有 frontmatter"))
        assertNull(parseFrontmatter(""))
        // Unterminated header is broken, not empty — also null.
        assertNull(parseFrontmatter("---\nname: wechat\n## 正文"))
        // And the pack parser inherits that leniency instead of throwing.
        assertNull(parseAppKnowledgePack("wechat", "# 没有 frontmatter"))
        assertNull(parseAppKnowledgePack("wechat", "---\nname: wechat\n## 正文"))
    }

    // 3. JSON array values → lists (two packages, quoted and bare forms).
    @Test
    fun parsesStringLists() {
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.other"), parseKnowledgeStringList("[\"com.tencent.mm\", \"com.tencent.mm.other\"]"))
        assertEquals(listOf("a", "b"), parseKnowledgeStringList("""["a","b"]"""))
        assertEquals(listOf("com.tencent.mm"), parseKnowledgeStringList("com.tencent.mm"))
        assertEquals(listOf("a", "b"), parseKnowledgeStringList("a, b"))
        assertEquals(emptyList<String>(), parseKnowledgeStringList(""))
        assertEquals(emptyList<String>(), parseKnowledgeStringList("[not json"))

        val twoPackages = parseAppKnowledgePack(
            "multi",
            """
            ---
            name: multi
            display: 多包名
            packages: ["com.a.one", "com.a.two"]
            aliases: ["One"]
            ---

            ## 页面结构
            - 正文。
            """.trimIndent(),
        )
        assertEquals(listOf("com.a.one", "com.a.two"), requireNotNull(twoPackages).entry.packages)
    }

    // 4. Package name resolves to the pack.
    @Test
    fun findsByPackageName() {
        val entry = index.find("com.tencent.mm")
        assertEquals("wechat", entry?.id)
        assertEquals("com.eg.android.AlipayGphone", index.find("com.eg.android.AlipayGphone")?.packages?.first())
        assertEquals("wechat", index.byId("wechat")?.id)
    }

    // 5. Display name / alias resolve, case-insensitively.
    @Test
    fun findsByAliasCaseInsensitively() {
        assertEquals("wechat", index.find("微信")?.id)
        assertEquals("wechat", index.find("wechat")?.id)
        assertEquals("wechat", index.find("WeChat")?.id)
        assertEquals("wechat", index.find("  COM.TENCENT.MM  ")?.id)
        assertEquals("wechat", index.find("Weixin")?.id)
        assertEquals("alipay", index.find("支付宝")?.id)
        // Substring fallback stays available for the two-char floor: a broader
        // name still reaches the pack whose alias it contains.
        assertEquals("alipay", index.find("支付")?.id)
    }

    // 6. Unknown app → null (the tool turns this into the "available:" list).
    @Test
    fun unknownQueryReturnsNull() {
        assertNull(index.find("com.example.nothing"))
        assertNull(index.find("不存在"))
        assertNull(index.find(""))
        assertNull(index.find("   "))
        // One-char queries never hit the substring fallback.
        assertNull(index.find("微"))
    }

    // Pack-level guards: a pack without packages, or with an empty body, is skipped.
    @Test
    fun rejectsUnusablePacks() {
        assertNull(parseAppKnowledgePack("x", "---\nname: x\ndisplay: X\n---\n\n## 页面结构\n- 无包名。"))
        assertNull(parseAppKnowledgePack("x", "---\nname: x\npackages: [\"com.x\"]\n---\n\n"))
        val usable = parseAppKnowledgePack(
            "x",
            "---\nname: xx\ndisplay: X\npackages: [\"com.x\"]\n---\n\n## 页面结构\n- 正文。",
        )
        assertNotNull(usable)
        // `name` doubles as an alias when it is not already the id/display.
        assertTrue(requireNotNull(usable).entry.aliases.contains("xx"))
    }

    // Rendered tool output: body + provenance, no frontmatter leakage.
    @Test
    fun rendersBodyWithSourceLine() {
        val entry = requireNotNull(index.find("com.tencent.mm"))
        val rendered = renderAppKnowledge(entry, "## 页面结构\n- 底部四个标签。")
        assertTrue(rendered.contains("微信"))
        assertTrue(rendered.contains("com.tencent.mm"))
        assertTrue(rendered.contains("## 页面结构"))
        assertTrue(rendered.contains("assets/app_knowledge/wechat/APP.md"))
        assertTrue("frontmatter must not reach the model", !rendered.contains("aliases:"))
        assertTrue("empty index still yields a message", AppKnowledgeIndex(emptyList()).availableList().isEmpty())
        assertTrue(index.availableList().contains("wechat (微信, com.tencent.mm)"))
    }
}
