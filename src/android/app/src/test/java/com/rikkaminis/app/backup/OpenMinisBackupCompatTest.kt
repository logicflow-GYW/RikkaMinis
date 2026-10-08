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

    private fun messageLine() = JSONObject()
        .put("t", "MessageV2")
        .put("v", 1)
        .put(
            "d",
            JSONObject()
                .put("id", "msg-1")
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
}
