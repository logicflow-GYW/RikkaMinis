package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * OpenMinisBackupCompat 的 JVM 端到端测试：按 OpenMinis `minisbak/1`
 * 规范（BackupFormat.kt / BackupExporter.kt / BackupCrypto.kt）在内存里
 * 构造合成备份包，验证转换输出符合 RikkaMinis `ConfigBackup.import` 的
 * 读取契约（字段名、时间格式、_entryIds 重映射等）。
 *
 * 加密用例独立复刻 minisbak-enc/1 的密钥推导（PBKDF2 → HKDF → AES-GCM
 * 分段，AAD = "<成员名>#<段号>"），与实现交叉验证。
 */
class OpenMinisBackupCompatTest {

    // ------------------------------------------------------------------
    // 合成包构造
    // ------------------------------------------------------------------

    /** OpenMinis 的包成员一律 STORED（BackupZip.kt）。 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.size = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun jsonEntry(o: JSONObject) = o.toString().toByteArray(StandardCharsets.UTF_8)
    private fun textEntry(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /**
     * 真实包形态：**不带 `format` 键**。OpenMinis 用 kotlinx.serialization 写
     * manifest，而 `BackupFormat.json` 没开 `encodeDefaults`，`format` 的默认值
     * 就是 "minisbak/1"，于是该字段在 JSON 里被整个省略。实测 OpenMinis 1.14
     * build 28 导出的包，manifest 顶层只有 created_at / snapshot_at / app /
     * device_name / backup_id / categories / integrity。
     *
     * 夹具曾经硬写 `.put("format","minisbak/1")`，让实现里"字段缺失即拒绝"
     * 的 bug 永远测不出来 —— 夹具必须复刻真包，不是复刻想象。
     */
    private fun plainManifest() = JSONObject()
        .put("created_at", "2026-10-01T08:30:00Z")

    private fun providerConfig(): JSONObject {
        val model = JSONObject()
            .put("id", "claude-fable-5")
            .put("displayName", "Claude Fable 5")
            .put("provider", "Anthropic")
            .put("contextWindow", 1_000_000)
            .put("maxOutputTokens", 128_000)
            .put("supportsReasoning", true)
            .put("inputModalities", JSONArray().put("text").put("image"))
            .put("outputModalities", JSONArray().put("text"))
        val entry = JSONObject()
            .put("providerInstanceId", "inst-1")
            .put("model", model)
            .put("overrides", JSONObject().put("displayName", "Fable 5 (工作)"))
            .put("isCustom", false)
            .put("isHidden", false)
            .put("uuid", "entry-1")
            .put("userModifiedAt", "2026-07-20T12:00:00Z")
        val instance = JSONObject()
            .put("id", "inst-1")
            .put("providerType", "anthropic")
            .put("label", "My Anthropic")
            .put("credentialType", "apiKey")
            .put("createdAt", "2026-06-10T12:00:00Z")
        val group = JSONObject()
            .put("id", "g1")
            .put("name", "Main")
            .put("memberEntryIds", JSONArray().put("entry-1"))
            .put("strategy", "fallback")
        return JSONObject()
            .put("instances", JSONArray().put(instance))
            .put("modelEntries", JSONArray().put(entry))
            .put("modelGroups", JSONArray().put(group))
    }

    private fun secretsJson() = JSONObject()
        .put(
            "providers",
            JSONArray().put(
                JSONObject()
                    .put("instanceId", "inst-1")
                    .put("label", "My Anthropic")
                    .put("providerType", "anthropic")
                    .put(
                        "apiKey",
                        Base64.getEncoder()
                            .encodeToString("sk-test-1234".toByteArray()),
                    )
            )
        )
        .put(
            "envVars",
            JSONArray().put(
                JSONObject()
                    .put("name", "MY_KEY")
                    .put(
                        "value",
                        Base64.getEncoder()
                            .encodeToString("secret-value".toByteArray()),
                    )
            )
        )

    /**
     * 真实包形态：**顶层 JSON 数组**，没有 wrapper 对象
     * （`[{id,key,note,createdAt},…]`）。夹具曾经写成 `{"entries":[…]}`，
     * 于是"顶层是数组"这条真实路径从未被覆盖过。
     */
    private fun envVarsMeta() = JSONArray().put(
        JSONObject().put("id", "ev-1").put("key", "MY_KEY")
            .put("note", "测试").put("createdAt", "2026-09-01T00:00:00Z")
    )

    private fun thinkingRulesLine() = JSONObject()
        .put("t", "ThinkingRuleV1")
        .put("v", 1)
        .put(
            "d",
            JSONObject()
                .put("id", "rule-1")
                .put("instanceId", "inst-1")
                .put("sortOrder", 0)
                .put("scopeKind", "allModels")
                .put("wireFormatJson", "{\"budget_tokens\":4096}")
                .put("label", "Fable 默认"),
        )
        .toString()

    private fun sessionLine(memoryEnabled: Boolean) = JSONObject()
        .put("t", "SessionV2")
        .put("v", 1)
        .put(
            "d",
            JSONObject()
                .put(
                    "session",
                    JSONObject()
                        .put("id", "sess-1")
                        .put("title", "周报")
                        .put("modelId", "claude-fable-5")
                        .put("createdAt", "2026-09-30T08:00:00Z")
                        .put("updatedAt", "2026-09-30T08:05:00Z")
                )
                .put("memoryEnabled", memoryEnabled)
                .put("modelBinding", "group:g1")
        )
        .toString()

    private fun messageLine(id: String = "msg-1") = JSONObject()
        .put("t", "MessageV2")
        .put("v", 1)
        .put(
            "d",
            JSONObject()
                .put("id", id)
                .put("sessionId", "sess-1")
                .put("role", "user")
                .put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("value", "帮我整理周报")
                    )
                )
                .put("createdAt", "2026-09-30T08:00:05Z")
                .put("sortOrder", 0)
                .put("reasoningContent", ""),
        )
        .toString()

    private fun skillMembers(): List<Pair<String, ByteArray>> {
        val skillMd = textEntry(
            "---\nname: Web 摘要\ndescription: 检索并摘要网页\nversion: 1.2.0\n---\n\n# Web 摘要\n"
        )
        val script = textEntry("#!/usr/bin/env python3\nprint('tool')\n")
        val shaMd = sha256Hex(skillMd)
        val shaPy = sha256Hex(script)
        val index = textEntry(
            JSONObject().put("path", "skills/web-summary/SKILL.md")
                .put("size", skillMd.size).put("sha256", shaMd).toString() +
                "\n" +
                JSONObject().put("path", "skills/web-summary/scripts/tool.py")
                    .put("size", script.size).put("sha256", shaPy).toString() +
                "\n"
        )
        /**
         * 真实包形态：blob 落在**两级分片**路径 `blobs/<sha[:2]>/<sha256>`
         * （BackupBlobStore.blobFile = File(File(blobsRoot, digest.take(2)),
         * digest)），不是扁平的 `blobs/<sha>`。扁平布局的回退路径由
         * [flatBlobLayoutStillImports] 单独覆盖。
         */
        return listOf(
            "files.index.jsonl" to index,
            "blobs/${shaMd.take(2)}/$shaMd" to skillMd,
            "blobs/${shaPy.take(2)}/$shaPy" to script,
        )
    }

    private fun mcpJson() = JSONObject()
        .put(
            "mcpServers",
            JSONObject().put(
                "fetch",
                JSONObject()
                    .put("command", "python")
                    .put("args", JSONArray().put("-m").put("minis_mcp_fetch"))
            )
        )

    /** 全类别明文合成包。 */
    private fun plainPackage(): ByteArray {
        val members = mutableListOf<Pair<String, ByteArray>>()
        members += "manifest.json" to jsonEntry(plainManifest())
        members += "data/provider_config.json" to jsonEntry(providerConfig())
        members += "data/thinking_rules.jsonl" to textEntry(thinkingRulesLine() + "\n")
        members += "data/env_vars.json" to textEntry(envVarsMeta().toString())
        members += "secrets.json" to jsonEntry(secretsJson())
        members += "data/mcp_servers.json" to jsonEntry(mcpJson())
        members += "data/memory/GLOBAL.md" to textEntry("全局记忆：偏好简洁回答。")
        members += "data/sessions.jsonl" to textEntry(sessionLine(true) + "\n")
        members += "data/messages.jsonl" to textEntry(messageLine() + "\n")
        members += skillMembers()
        return zipOf(*members.toTypedArray())
    }

    // ------------------------------------------------------------------
    // minisbak-enc/1 加密构造（独立复刻规范，交叉验证解密实现）
    // ------------------------------------------------------------------

    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun encryptMember(raw: ByteArray, key: ByteArray, memberName: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)),
        )
        // AAD 绑定 "成员名#段号"（BackupCrypto.aad()：路径含 .enc 后缀，段号从 0 起）
        cipher.updateAAD("$memberName#0".toByteArray(StandardCharsets.UTF_8))
        val sealed = cipher.doFinal(raw) // ct ‖ tag
        val out = ByteArrayOutputStream()
        out.write("MBK1".toByteArray(StandardCharsets.US_ASCII))
        val seg = ByteArray(4)
        val len = 12 + sealed.size
        seg[0] = ((len ushr 24) and 0xFF).toByte()
        seg[1] = ((len ushr 16) and 0xFF).toByte()
        seg[2] = ((len ushr 8) and 0xFF).toByte()
        seg[3] = (len and 0xFF).toByte()
        out.write(seg)
        out.write(cipher.iv!!)
        out.write(sealed)
        return out.toByteArray()
    }

    private fun encryptedPackage(passphrase: String): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val kek = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, 1000, 256))
            .encoded
        val dataKey = hkdf(kek, "minisbak/data")
        val manifest = plainManifest()
            .put(
                "encryption",
                JSONObject()
                    .put("scheme", "minisbak-enc/1")
                    .put("verifier", "unused")
                    .put(
                        "kdf",
                        JSONObject()
                            .put("alg", "pbkdf2-hmac-sha256")
                            .put(
                                "salt",
                                Base64.getEncoder().encodeToString(salt),
                            )
                            .put("iterations", 1000)
                    )
            )
        // 只加密两个成员：provider_config（常规路径）与 memory（.enc 目录遍历路径）
        val pcEnc = encryptMember(
            jsonEntry(providerConfig()), dataKey, "data/provider_config.json.enc"
        )
        val memEnc = encryptMember(
            textEntry("加密包里的记忆。"), dataKey, "data/memory/GLOBAL.md.enc"
        )
        return zipOf(
            "manifest.json" to jsonEntry(manifest),
            "data/provider_config.json.enc" to pcEnc,
            "data/memory/GLOBAL.md.enc" to memEnc,
        )
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    @Test
    fun rejectsNonZipInput() {
        assertFalse(
            OpenMinisBackupCompat.looksLikeMinisBak(
                "{\"format\":\"openminis.config.backup\"}".toByteArray()
            )
        )
    }

    @Test
    fun acceptsManifestWithoutFormatKey() {
        // 回归：真实包里没有 format 键（encodeDefaults=false）。曾经因为
        // optString("format","") 取到空串而硬拒，等于 100% 拒收真包。
        val pkg = zipOf("manifest.json" to jsonEntry(plainManifest()))
        val root = JSONObject(OpenMinisBackupCompat.convert(pkg))
        assertEquals("openminis.config.backup", root.getString("format"))
    }

@Test
    fun envVarsTopLevelArrayIsAccepted() {
        // 回归：data/env_vars.json 真实形态是顶层数组。实现曾无条件
        // JSONObject(text)，解析失败后 ?: return out 把环境变量静默丢成 0 条。
        val root = JSONObject(OpenMinisBackupCompat.convert(plainPackage()))
        val ev = root.getJSONArray("envVars")
        assertEquals(1, ev.length())
        assertEquals("MY_KEY", ev.getJSONObject(0).getString("key"))
        assertEquals("secret-value", ev.getJSONObject(0).getString("value"))
    }

    @Test
    fun shardedBlobLayoutIsUsed() {
        // 回归：blob 在 blobs/<sha[:2]>/<sha>。实现曾按扁平 blobs/<sha> 读，
        // 结果每个技能的 archive 都是空 zip（readMember 返回 null 被 ?: continue
        // 吞掉），导入后技能全空且不报错。
        val root = JSONObject(OpenMinisBackupCompat.convert(plainPackage()))
        val sk = root.getJSONArray("skills")
        assertEquals(1, sk.length())
        val archive = Base64.getDecoder().decode(sk.getJSONObject(0).getString("archive"))
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
            var e: java.util.zip.ZipEntry? = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertEquals(listOf("SKILL.md", "scripts/tool.py"), names.sorted())
    }

    @Test
    fun flatBlobLayoutStillImports() {
        // 回退路径：更老的包把 blob 平铺在 blobs/<sha>，仍要能导入。
        val members = mutableListOf<Pair<String, ByteArray>>()
        members += "manifest.json" to jsonEntry(plainManifest())
        for ((name, data) in skillMembers()) {
            members += if (name.startsWith("blobs/")) {
                "blobs/" + name.substringAfterLast('/') to data
            } else {
                name to data
            }
        }
        val root = JSONObject(OpenMinisBackupCompat.convert(zipOf(*members.toTypedArray())))
        val sk = root.getJSONArray("skills")
        assertEquals(1, sk.length())
        assertEquals("web-summary", sk.getJSONObject(0).getString("id"))
    }

    @Test
    fun detectsEncryptedPackageWithoutASecret() {
        // 回归：加密包必须在让用户输口令**之前**就被识别出来。
        // requiresPassphrase 只流式读 manifest，不解成员。
        assertFalse(
            OpenMinisBackupCompat.requiresPassphrase(
                zipOf("manifest.json" to jsonEntry(plainManifest()))
            )
        )
        assertTrue(OpenMinisBackupCompat.requiresPassphrase(encryptedPackage("pw")))
        // 非 ZIP 输入不炸，交给 convert() 报真错。
        assertFalse(OpenMinisBackupCompat.requiresPassphrase("not a zip".toByteArray()))
    }

    @Test
    fun rejectsUnknownFormat() {
        val bad = zipOf(
            "manifest.json" to jsonEntry(JSONObject().put("format", "minisbak/2"))
        )
        try {
            OpenMinisBackupCompat.convert(bad)
            fail("应拒绝非 minisbak/1 格式")
        } catch (e: OpenMinisBackupCompat.MinisBakException) {
            assertTrue(e.message!!.contains("minisbak/1"))
        }
    }

    @Test
    fun convertsPlaintextPackageEndToEnd() {
        val out = OpenMinisBackupCompat.convert(plainPackage())
        val root = JSONObject(out)

        assertEquals("openminis.config.backup", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertTrue(root.getLong("exportedAt") > 0)

        // providers：嵌套 model 拍平 + ISO→毫秒 + secrets 透传
        val prov = root.getJSONArray("providers")
        assertEquals(1, prov.length())
        val p = prov.getJSONObject(0)
        assertEquals("anthropic", p.getString("providerType"))
        assertEquals("My Anthropic", p.getString("label"))
        assertEquals("apiKey", p.getString("credentialType"))
        // 2026-06-10T12:00:00Z
        assertEquals(1781092800000L, p.getLong("createdAt"))
        assertEquals("sk-test-1234", String(
            Base64.getDecoder().decode(p.getString("apiKey"))
        ))
        val models = p.getJSONArray("models")
        assertEquals(1, models.length())
        val m = models.getJSONObject(0)
        assertEquals("claude-fable-5", m.getString("modelId"))
        assertEquals(1_000_000, m.getInt("contextWindow"))
        assertTrue(m.has("supportsReasoning"))
        assertEquals("Fable 5 (工作)", m.getJSONObject("overrides").getString("displayName"))
        // 2026-07-20T12:00:00Z
        assertEquals(1784548800000L, m.getLong("userModifiedAt"))
        val entryIds = p.getJSONArray("_entryIds")
        assertEquals(1, entryIds.length())
        assertEquals("entry-1", entryIds.getString(0))

        // thinkingRules：按 instanceId → (providerType, label) 重挂
        val rules = root.getJSONArray("thinkingRules")
        assertEquals(1, rules.length())
        val r = rules.getJSONObject(0)
        assertEquals("anthropic", r.getString("providerType"))
        assertEquals("My Anthropic", r.getString("providerLabel"))
        assertEquals("{\"budget_tokens\":4096}",
            r.getJSONArray("rules").getJSONObject(0).getString("wireFormatJson"))

        // groups：memberEntryIds 原样（RikkaMinis 导入端经 _entryIds 重映射）
        val g = root.getJSONArray("groups").getJSONObject(0)
        assertEquals("Main", g.getString("name"))
        assertEquals("entry-1", g.getJSONArray("memberEntryIds").getString(0))

        // envVars：secrets 的 base64 值解回明文
        val ev = root.getJSONArray("envVars").getJSONObject(0)
        assertEquals("MY_KEY", ev.getString("key"))
        assertEquals("secret-value", ev.getString("value"))
        assertEquals("测试", ev.getString("note"))

        // skills：blob 内容按 files.index 重组为 zip（base64）
        val sk = root.getJSONArray("skills").getJSONObject(0)
        assertEquals("web-summary", sk.getString("id"))
        assertEquals("Web 摘要", sk.getString("name"))
        val archive = Base64.getDecoder().decode(sk.getString("archive"))
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
            var e: java.util.zip.ZipEntry? = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue(names.contains("SKILL.md"))
        assertTrue(names.contains("scripts/tool.py"))

        // memoryFiles
        val mem = root.getJSONArray("memoryFiles").getJSONObject(0)
        assertEquals("GLOBAL.md", mem.getString("name"))
        assertEquals("全局记忆：偏好简洁回答。", mem.getString("content"))

        // mcpServers：逐服务器包装
        val mcp = root.getJSONArray("mcpServers").getJSONObject(0)
            .getJSONObject("mcpServers").getJSONObject("fetch")
        assertEquals("python", mcp.getString("command"))

        // chatSessions：memoryEnabled Boolean → Int（1/0）
        val sess = root.getJSONArray("chatSessions").getJSONObject(0)
        assertEquals("sess-1", sess.getString("id"))
        assertEquals(1, sess.getInt("memoryEnabled"))
        assertEquals("group:g1", sess.getString("modelBinding"))
        // 2026-09-30T08:00:00Z
        assertEquals(1790755200000L, sess.getLong("createdAt"))

        // chatMessages：parts 数组 → partsJson 字符串
        val msg = root.getJSONArray("chatMessages").getJSONObject(0)
        assertEquals("msg-1", msg.getString("id"))
        val parts = JSONArray(msg.getString("partsJson"))
        assertEquals("帮我整理周报", parts.getJSONObject(0).getString("value"))
    }

    @Test
    fun memoryEnabledFalseMapsToZero() {
        val pkg = zipOf(
            "manifest.json" to jsonEntry(plainManifest()),
            "data/sessions.jsonl" to textEntry(sessionLine(false) + "\n"),
        )
        val sess = JSONObject(OpenMinisBackupCompat.convert(pkg))
            .getJSONArray("chatSessions").getJSONObject(0)
        assertEquals(0, sess.getInt("memoryEnabled"))
    }

    @Test
    fun decryptsEncryptedPackage() {
        val pkg = encryptedPackage("correct horse battery staple")
        val root = JSONObject(OpenMinisBackupCompat.convert(
            pkg, "correct horse battery staple".toCharArray()
        ))
        val p = root.getJSONArray("providers").getJSONObject(0)
        assertEquals("anthropic", p.getString("providerType"))
        assertEquals("My Anthropic", p.getString("label"))
        // 加密包的 memory（.enc 成员）也必须被转换
        val mem = root.getJSONArray("memoryFiles").getJSONObject(0)
        assertEquals("GLOBAL.md", mem.getString("name"))
        assertEquals("加密包里的记忆。", mem.getString("content"))
    }

    @Test
    fun wrongPassphraseFailsCleanly() {
        val pkg = encryptedPackage("correct horse battery staple")
        try {
            OpenMinisBackupCompat.convert(pkg, "wrong".toCharArray())
            fail("错误口令应失败")
        } catch (e: OpenMinisBackupCompat.MinisBakException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun encryptedPackageWithoutPassphraseFails() {
        val pkg = encryptedPackage("correct horse battery staple")
        try {
            OpenMinisBackupCompat.convert(pkg)
            fail("缺省口令应失败")
        } catch (e: OpenMinisBackupCompat.MinisBakException) {
            assertTrue(e.message!!.contains("口令"))
        }
    }

    // ------------------------------------------------------------------
    // [fix-minisbak-compat] 回归测试
    // ------------------------------------------------------------------

    @Test
    fun isoToMillisAcceptsAllFourOpenMinisIsoShapes() {
        // OpenMinis 自家的 BackupRecordMapper.millis() 接受四种 ISO 形态
        //（有无毫秒小数 × 'Z'/时区偏移）。kotlinx Instant 序列化在毫秒
        // 非零时输出 ".SSS"——真实包几乎必然带小数位。原先只认整秒形态，
        // 命不中就把时间戳静默归零（createdAt/updatedAt=0、provider 落
        // 到 now()），导入后整条时间线失真。
        assertEquals(1780142405123L, OpenMinisBackupCompat.isoToMillis("2026-05-30T12:00:05.123Z"))
        assertEquals(1780142405000L, OpenMinisBackupCompat.isoToMillis("2026-05-30T12:00:05Z"))
        assertEquals(1780142405000L, OpenMinisBackupCompat.isoToMillis("2026-05-30T20:00:05+08:00"))
        assertEquals(1780142405123L, OpenMinisBackupCompat.isoToMillis("2026-05-30T20:00:05.123+08:00"))
        // 6 位小数（iOS Swift 常见输出）也必须能解析
        assertEquals(1780142405123L, OpenMinisBackupCompat.isoToMillis("2026-05-30T12:00:05.123456Z"))
        // 裸 epoch 与数值直通不受影响
        assertEquals(1780142405000L, OpenMinisBackupCompat.isoToMillis(1780142405000L))
        assertEquals(1780142405000L, OpenMinisBackupCompat.isoToMillis("1780142405000"))
        // 垃圾输入仍返回 null（走调用方的回退）
        assertEquals(null, OpenMinisBackupCompat.isoToMillis("not-a-date"))
    }

    @Test
    fun truncatedEncryptedSegmentFailsCleanly() {
        // 回归：段长 < nonce(12) + GCM tag(16) 时，copyOfRange 的
        // from > to 会抛裸 IllegalArgumentException 而不是
        // MinisBakException——错误语义绕开了统一的"加密成员损坏"。
        val salt = ByteArray(16) { (it + 1).toByte() }
        val kek = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("pw".toCharArray(), salt, 1000, 256)).encoded
        val manifest = plainManifest().put(
            "encryption",
            JSONObject()
                .put("scheme", "minisbak-enc/1")
                .put("kdf", JSONObject()
                    .put("alg", "pbkdf2-hmac-sha256")
                    .put("salt", Base64.getEncoder().encodeToString(salt))
                    .put("iterations", 1000)),
        )
        val corrupt = ByteArrayOutputStream().apply {
            write("MBK1".toByteArray(StandardCharsets.US_ASCII))
            val len = 8 // 连 12 字节 nonce 都放不下
            write(
                byteArrayOf(
                    ((len ushr 24) and 0xFF).toByte(),
                    ((len ushr 16) and 0xFF).toByte(),
                    ((len ushr 8) and 0xFF).toByte(),
                    (len and 0xFF).toByte(),
                ),
            )
            write(ByteArray(8))
        }.toByteArray()
        assert(kek.isNotEmpty())
        val pkg = zipOf(
            "manifest.json" to jsonEntry(manifest),
            "data/provider_config.json.enc" to corrupt,
        )
        try {
            OpenMinisBackupCompat.convert(pkg, "pw".toCharArray())
            fail("截断的加密段应失败")
        } catch (e: OpenMinisBackupCompat.MinisBakException) {
            assertNotNull(e.message)
        }
    }

    // ------------------------------------------------------------------
    // [fix-minisbak-append-v1-parity] + [fix-minisbak-compat-parity]
    // 1022 ISSUE-04：转换层字段极性与字段完整性
    // ------------------------------------------------------------------

    @Test
    fun providersAppendV1SuffixIsThreeStateNotPolarityFlipped() {
        // 回归：appendV1Suffix 两端默认 true——RikkaMinis 导入端
        // optBoolean("appendV1Suffix", true)，Android 自家导出只在 false 时
        // 写键（缺键=默认 true）。旧实现照抄隔壁 useResponsesAPI 的「默认
        // false、只在 true 时写」形态（那行对 useResponsesAPI 恰好正确——它的
        // 导入默认就是 false），套在极性相反的字段上 → iOS 显式 false 的实例
        // 丢键后被导回 true，每个请求平白多出 /v1 后缀（非 /v1 网关 404）。
        fun inst(id: String, flag: Boolean?): JSONObject {
            val j = JSONObject()
                .put("id", id)
                .put("providerType", "anthropic")
                .put("label", id)
                .put("credentialType", "apiKey")
                .put("createdAt", "2026-06-10T12:00:00Z")
            if (flag != null) j.put("appendV1Suffix", flag)
            return j
        }
        val pc = JSONObject().put(
            "instances",
            JSONArray().put(inst("inst-on", true)).put(inst("inst-off", false))
                .put(inst("inst-absent", null))
        )
        val root = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(plainManifest()),
                    "data/provider_config.json" to jsonEntry(pc),
                )
            )
        )
        val prov = root.getJSONArray("providers")
        assertEquals(3, prov.length())
        val on = prov.getJSONObject(0)
        val off = prov.getJSONObject(1)
        val absent = prov.getJSONObject(2)
        // 显式 true：键与值都保真
        assertTrue(on.has("appendV1Suffix"))
        assertTrue(on.getBoolean("appendV1Suffix"))
        // 显式 false：必须显式落键——修复前这里丢键，导入端读回 true（反转）
        assertTrue(off.has("appendV1Suffix"))
        assertFalse(off.getBoolean("appendV1Suffix"))
        // 缺键：保持缺键（导入端默认 true；透传不伪造值，iOS 缺键语义无从
        // 证实，与旧行为一致）
        assertFalse(absent.has("appendV1Suffix"))
        // 对照组：useResponsesAPI 的「默认 false」极性不受本次修复影响
        assertFalse(on.has("useResponsesAPI"))
        assertFalse(off.has("useResponsesAPI"))
    }

    @Test
    fun providersCarryEffortTierPairAndCostTier() {
        // 回归：1021 修好了 Android 自家导出/导入的 reasoningEffortValues +
        // declaresNoEffortTiers（iOS LLMModel 本就携带两者，见 LLMModel.kt
        // "Mirrors iOS LLMModel.…" 注释），但 OpenMinis 转换层没跟进 → 跨端
        // 恢复静默蒸发 effort 档位上限与 xAI「无 effort 档位」声明，只对
        // models.dev 查不到的网关模型生效。costTier（cheapestFirst 路由）同族。
        val model = JSONObject()
            .put("id", "glm-4.7-plus")
            .put("displayName", "GLM 4.7 Plus")
            .put("provider", "zhipuai")
            .put("reasoningEffortValues", JSONArray().put("high").put("medium"))
            .put("declaresNoEffortTiers", false)
        val bareModel = JSONObject()
            .put("id", "bare-model")
            .put("displayName", "Bare")
            .put("provider", "zhipuai")
            .put("reasoningEffortValues", JSONArray())
            .put("declaresNoEffortTiers", JSONObject.NULL)
        val entry = JSONObject()
            .put("providerInstanceId", "inst-1")
            .put("model", model)
            .put("costTier", 2)
        val bareEntry = JSONObject()
            .put("providerInstanceId", "inst-1")
            .put("model", bareModel)
        val instance = JSONObject()
            .put("id", "inst-1")
            .put("providerType", "zhipuai")
            .put("label", "GLM 网关")
            .put("credentialType", "apiKey")
            .put("createdAt", "2026-06-10T12:00:00Z")
        val pc = JSONObject()
            .put("instances", JSONArray().put(instance))
            .put("modelEntries", JSONArray().put(entry).put(bareEntry))
        val root = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(plainManifest()),
                    "data/provider_config.json" to jsonEntry(pc),
                )
            )
        )
        val models = root.getJSONArray("providers").getJSONObject(0)
            .getJSONArray("models")
        assertEquals(2, models.length())
        val full = models.getJSONObject(0)
        assertEquals("high", full.getJSONArray("reasoningEffortValues").getString(0))
        assertEquals("medium", full.getJSONArray("reasoningEffortValues").getString(1))
        // 三态：显式 false 落键（不得塌缩成缺键——导入端缺键语义是 null）
        assertTrue(full.has("declaresNoEffortTiers"))
        assertFalse(full.getBoolean("declaresNoEffortTiers"))
        assertEquals(2, full.getInt("costTier"))
        // 守卫透传的另一半：空数组/显式 null/缺键一律不硬造
        val bare = models.getJSONObject(1)
        assertFalse(bare.has("reasoningEffortValues"))
        assertFalse(bare.has("declaresNoEffortTiers"))
        assertFalse(bare.has("costTier"))
    }

    @Test
    fun chatSessionsPinnedAtSurvivesAsPositiveMillis() {
        // 回归：ConfigBackup 导出写 pinnedAt、导入按 has 读取（null=未置顶）。
        // 转换层此前整个字段缺失 → 跨端恢复丢会话置顶状态。0/负值不写：
        // 导入端会把它当成「置顶于纪元零点」。
        fun sessionLine(id: String, pinned: Any?): String {
            val d = JSONObject()
                .put(
                    "session",
                    JSONObject()
                        .put("id", id)
                        .put("title", "置顶测试")
                        .put("modelId", "claude-fable-5")
                        .put("createdAt", "2026-09-30T08:00:00Z")
                        .put("updatedAt", "2026-09-30T08:05:00Z")
                )
                .put("memoryEnabled", true)
            if (pinned != null) d.put("pinnedAt", pinned)
            return JSONObject().put("t", "SessionV2").put("v", 1).put("d", d).toString()
        }
        val lines = sessionLine("sess-iso", "2026-10-01T09:30:00Z") + "\n" +
            sessionLine("sess-zero", 0) + "\n" +
            sessionLine("sess-absent", null) + "\n"
        val root = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(plainManifest()),
                    "data/sessions.jsonl" to textEntry(lines),
                )
            )
        )
        val sessions = root.getJSONArray("chatSessions")
        assertEquals(3, sessions.length())
        val iso = sessions.getJSONObject(0)
        // 2026-10-01T09:30:00Z（ISO 形态上游可能用，裸 Long 同理）
        assertTrue(iso.has("pinnedAt"))
        assertEquals(1790847000000L, iso.getLong("pinnedAt"))
        // 0 → 不写（导入端 Long 读回 0 会变成「置顶于纪元零点」）
        assertFalse(sessions.getJSONObject(1).has("pinnedAt"))
        // 缺键 → 不写（不硬造）
        assertFalse(sessions.getJSONObject(2).has("pinnedAt"))
    }

    // ------------------------------------------------------------------
    // [deep-audit-1039 A-07] groups[] export-key completeness
    // ------------------------------------------------------------------

    /**
     * `convertGroups` 必须把上游 provider_config.json 的 modelGroups 条目里
     * 的**全部导出键**写进 groups[]。ConfigBackup.buildSections 导出 8 个键
     * （id/name/memberEntryIds/strategy/fallbackStrategy/defaultThinkingLevel/
     * contextLimitTokens/lastContextLimitTokens），导入侧 readGroupRunConfig /
     * applyGroupRunConfig（[fix/dv18-4 A-07]）已能读回其中 5 个 run-config 键；
     * 这里漏一个，备份恢复就静默重置用户的路由/思考/上下文设置。
     *
     * 期望值一律写死字面量（不从被测代码推导）；导出侧键集合由门
     * openminis_compat_field_parity_check 的 arm4 从 ConfigBackup 派生并钉住。
     */
    @Test
    fun convertGroupsWritesEveryModelGroupExportKey() {
        val group = JSONObject()
            .put("id", "g1")
            .put("name", "Main")
            .put("memberEntryIds", JSONArray().put("entry-1"))
            .put("strategy", "loadBalance")
            .put("fallbackStrategy", "always")
            .put("defaultThinkingLevel", "MEDIUM")
            .put("contextLimitTokens", 128000)
            .put("lastContextLimitTokens", 96000)
        val pkg = zipOf(
            "manifest.json" to jsonEntry(plainManifest()),
            "data/provider_config.json" to jsonEntry(
                JSONObject().put("modelGroups", JSONArray().put(group))
            ),
        )
        val root = JSONObject(OpenMinisBackupCompat.convert(pkg))
        val groups = root.getJSONArray("groups")
        assertEquals(1, groups.length())
        val g = groups.getJSONObject(0)
        // 身份与成员
        assertEquals("g1", g.getString("id"))
        assertEquals("Main", g.getString("name"))
        assertEquals("entry-1", g.getJSONArray("memberEntryIds").getString(0))
        // 路由与回退（导入端读 .name 形态）
        assertEquals("loadBalance", g.getString("strategy"))
        assertEquals("always", g.getString("fallbackStrategy"))
        // 三个 run-config 键 —— 曾整族缺失，现在逐个钉住
        assertEquals("MEDIUM", g.getString("defaultThinkingLevel"))
        assertEquals(128000, g.getInt("contextLimitTokens"))
        assertEquals(96000, g.getInt("lastContextLimitTokens"))
    }

    /**
     * 反向臂：三个 run-config 键在上游**缺键**时不得硬造（导入端缺键语义 =
     * 「本地值优先」，见 ModelGroupRunConfigRestoreTest.legacyBackup…）。
     * 上游给空串 defaultThinkingLevel / 0 token 时同样不写。
     */
    @Test
    fun convertGroupsOmitsAbsentRunConfigKeys() {
        val legacy = JSONObject()
            .put("id", "g1")
            .put("name", "Main")
            .put("memberEntryIds", JSONArray().put("entry-1"))
            .put("strategy", "fallback")
            .put("defaultThinkingLevel", "")
            .put("contextLimitTokens", 0)
            .put("lastContextLimitTokens", 0)
        val pkg = zipOf(
            "manifest.json" to jsonEntry(plainManifest()),
            "data/provider_config.json" to jsonEntry(
                JSONObject().put("modelGroups", JSONArray().put(legacy))
            ),
        )
        val root = JSONObject(OpenMinisBackupCompat.convert(pkg))
        val g = root.getJSONArray("groups").getJSONObject(0)
        // 身份键照写
        assertEquals("g1", g.getString("id"))
        assertEquals("fallback", g.getString("strategy"))
        // 空/零值一律不写（不硬造）
        assertFalse(g.has("defaultThinkingLevel"))
        assertFalse(g.has("contextLimitTokens"))
        assertFalse(g.has("lastContextLimitTokens"))
        // fallbackStrategy 有默认值语义（导入端 create 路径 default）⇒ 照写
        assertEquals("default", g.getString("fallbackStrategy"))
    }

    // ------------------------------------------------------------------
    // [FIX-P1B / RT-S1] JSONL 分片缺失：缺片不是序列终点
    // ------------------------------------------------------------------

    private fun missingShardPackage(vararg entries: Pair<String, ByteArray>): ByteArray {
        val members = mutableListOf<Pair<String, ByteArray>>()
        members += "manifest.json" to jsonEntry(plainManifest())
        members += "data/sessions.jsonl" to textEntry(sessionLine(true) + "\n")
        members += entries
        return zipOf(*members.toTypedArray())
    }

    private fun idsOf(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }

    private fun namesOf(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getString(it) }

    /**
     * 世界 W3（分片文件消失）：`data/messages.jsonl` 与 `data/messages-0003.jsonl`
     * 都在，`-0002` 不在。
     *
     * 原实现 `readMember(...) ?: break` 把第一个空洞当序列终点 ⇒ 只读到 1 条，
     * `-0003` 及其后全部丢弃，且不留任何标记（导入侧看到的是一个「成功」的
     * 短历史）。判据 = 读到的条数等于**实际能读到的那几片之和**，且缺片数
     * 被报告出来。
     */
    @Test
    fun `missing middle message shard keeps later shards and reports the hole`() {
        val pkg = missingShardPackage(
            "data/messages.jsonl" to textEntry(messageLine("msg-1") + "\n"),
            "data/messages-0003.jsonl" to textEntry(messageLine("msg-3") + "\n"),
        )
        val doc = JSONObject(OpenMinisBackupCompat.convert(pkg, null))

        assertEquals(listOf("msg-1", "msg-3"), idsOf(doc.getJSONArray("chatMessages")))
        val trunc = doc.optJSONObject("chatTruncated")
        assertNotNull("缺片必须留下标记，不允许静默的部分恢复", trunc)
        assertEquals(1, trunc!!.getInt("shardsMissing"))
        assertEquals(1, trunc.getInt("messagesShardsMissing"))
        assertEquals(0, trunc.getInt("sessionsShardsMissing"))
        assertEquals(0, trunc.getInt("thinkingRulesShardsMissing"))
        assertEquals(listOf("data/messages-0002.jsonl"), namesOf(trunc.getJSONArray("missingShards")))
    }

    /**
     * 缺的是**第一片**（没有 `data/messages.jsonl`，只有 `-0002`）：仍要读到
     * `-0002` 的记录，并把第 1 片报成缺片。
     */
    @Test
    fun `missing first message shard still reads the second one`() {
        val pkg = missingShardPackage(
            "data/messages-0002.jsonl" to textEntry(messageLine("msg-2") + "\n"),
        )
        val doc = JSONObject(OpenMinisBackupCompat.convert(pkg, null))
        assertEquals(listOf("msg-2"), idsOf(doc.getJSONArray("chatMessages")))
        val trunc = doc.getJSONObject("chatTruncated")
        assertEquals(1, trunc.getInt("shardsMissing"))
        assertEquals(listOf("data/messages.jsonl"), namesOf(trunc.getJSONArray("missingShards")))
    }

    /**
     * 终点判据的反面：**末尾连续读不到的序号不是缺片**。
     *
     * 正常包在最后一片之后必然有一串读不到的序号（这里是 2、3、4…）。若把它们
     * 当成缺片，每个正常包的导入都会报「N 片缺失」。所以正常包必须完全不出现
     * `chatTruncated` 段（文档形状与升级前逐键一致）。
     */
    @Test
    fun `a complete package reports no shard loss`() {
        val doc = JSONObject(OpenMinisBackupCompat.convert(plainPackage(), null))
        assertFalse(
            "正常包不得出现缺片标记（末尾探测不算缺片）",
            doc.has("chatTruncated"),
        )
        assertEquals(1, doc.getJSONArray("chatMessages").length())
    }

    /**
     * 会话流与消息流各自独立：会话缺片不能牵连消息，反之亦然。
     */
    @Test
    fun `session shard hole does not implicate the message stream`() {
        val members = mutableListOf<Pair<String, ByteArray>>()
        members += "manifest.json" to jsonEntry(plainManifest())
        members += "data/sessions.jsonl" to textEntry(sessionLine(true) + "\n")
        members += "data/sessions-0003.jsonl" to textEntry(sessionLine(true) + "\n")
        members += "data/messages.jsonl" to textEntry(messageLine() + "\n")
        val doc = JSONObject(OpenMinisBackupCompat.convert(zipOf(*members.toTypedArray())))

        assertEquals(2, doc.getJSONArray("chatSessions").length())
        assertEquals(1, doc.getJSONArray("chatMessages").length())
        val trunc = doc.getJSONObject("chatTruncated")
        assertEquals(1, trunc.getInt("sessionsShardsMissing"))
        assertEquals(0, trunc.getInt("messagesShardsMissing"))
        assertEquals(listOf("data/sessions-0002.jsonl"), namesOf(trunc.getJSONArray("missingShards")))
    }

    /**
     * 关掉聊天迁移时根本不读聊天分片 ⇒ 聊天缺片不该报告（没读就不能声称缺）。
     * 但同一次转换里 thinking_rules 的缺片仍要报告 —— 报告段是「这个包不完整」，
     * 不只是「聊天不完整」。
     */
    @Test
    fun `skipping chats neither reports chat holes nor hides rule holes`() {
        val members = mutableListOf<Pair<String, ByteArray>>()
        members += "manifest.json" to jsonEntry(plainManifest())
        members += "data/provider_config.json" to jsonEntry(providerConfig())
        members += "data/thinking_rules.jsonl" to textEntry(thinkingRulesLine() + "\n")
        members += "data/thinking_rules-0003.jsonl" to textEntry(thinkingRulesLine() + "\n")
        members += "data/messages.jsonl" to textEntry(messageLine() + "\n")
        val doc = JSONObject(
            OpenMinisBackupCompat.convert(zipOf(*members.toTypedArray()), null, includeChats = false),
        )
        assertEquals(0, doc.getJSONArray("chatMessages").length())
        val trunc = doc.getJSONObject("chatTruncated")
        assertEquals(0, trunc.getInt("messagesShardsMissing"))
        assertEquals(0, trunc.getInt("sessionsShardsMissing"))
        assertEquals(1, trunc.getInt("thinkingRulesShardsMissing"))
        assertEquals(listOf("data/thinking_rules-0002.jsonl"), namesOf(trunc.getJSONArray("missingShards")))
    }
}
