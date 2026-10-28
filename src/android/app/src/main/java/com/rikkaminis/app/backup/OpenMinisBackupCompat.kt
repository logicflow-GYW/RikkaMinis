package com.rikkaminis.app.backup

/*
 * OpenMinisBackupCompat.kt — OpenMinis `.minisbak` → RikkaMinis 配置备份兼容层
 * ============================================================================
 *
 * 为什么需要这一层（根因）：
 *
 * OpenMinis 的备份是 ZIP 二进制包（manifest.json: format="minisbak/1"，内含
 * 分片 JSONL 记录、内容寻址 blob、可选 AES-GCM 加密）；RikkaMinis 的
 * ConfigBackup.import 期望一个扁平 JSON 文档（format="openminis.config.backup"）。
 * org.json 的 JSONTokener 是宽松解析器：ZIP 头 `PK\x03\x04…` 会被当成一个裸
 * 字符串字面量返回（不抛异常），`as? JSONObject` 得 null，最终抛出
 * "Backup root is not a JSON object"。本层把 .minisbak 在临时目录里解包、
 * 解密并逐字段转写成 RikkaMinis 的文档形态，产出一个可直接喂给
 * ConfigBackup.import 的 JSON 字符串。
 *
 * 用法（在读取备份文件的地方，String 化之前拦截）：
 *
 *     val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
 *         ?: return
 *     val json = if (OpenMinisBackupCompat.looksLikeMinisBak(bytes)) {
 *         OpenMinisBackupCompat.convert(bytes, passphrase)
 *     } else {
 *         String(bytes, Charsets.UTF_8)
 *     }
 *     val result = ConfigBackup().import(providerRepo, json, ...)
 *
 * 字段映射与 OpenMinis 仓库逐一对照（src/android/.../backup/BackupExporter.kt、
 * BackupFormat.kt、BackupZip.kt、BackupCrypto.kt）：
 *
 *   RikkaMinis 文档                ← OpenMinis .minisbak
 *   ─────────────────────────────────────────────────────────────
 *   format/version/exportedAt      ← 合成（"openminis.config.backup"/1/now）
 *   providers[]                    ← data/provider_config.json 的 instances
 *                                     + modelEntries（按 providerInstanceId 分组，
 *                                     model 嵌套对象拍平成 modelId/…，
 *                                     ISO-8601 时间转 epoch 毫秒）
 *                                     + secrets.json 的凭据（本就是 base64，透传）
 *                                     实例/模型键与 Android 自家导出
 *                                     （exportInstanceJSON）做集合奇偶校验，
 *                                     由 openminis_compat_field_parity_check 门
 *                                     钉住（appendV1Suffix 三态、reasoning 档对、
 *                                     costTier 等新增键自动进校验集）
 *   thinkingRules[]                ← data/thinking_rules.jsonl 按 instanceId 聚合，
 *                                     instanceId → (providerType, label)
 *   groups[]                       ← provider_config.json 的 modelGroups（直传，
 *                                     memberEntryIds 由 RikkaMinis 经 _entryIds 重映射）
 *   envVars[]                      ← data/env_vars.json（元数据）
 *                                     + secrets.json 的 envVars（base64 值解码为明文）
 *   skills[]                       ← skills/<id>/…（files.index.jsonl 索引 +
 *                                     blobs/<sha256> 内容）重打包为单技能 ZIP
 *                                     （SKILL.md 位于 zip 根，符合
 *                                     SkillRepository.importFromArchive 的查找规则）
 *   memoryFiles[]                  ← data/memory/…（嵌套路径以 "__" 拍平——
 *                                     RikkaMinis 的 memory 导入拒绝含 '/' 的名字）
 *   mcpServers[]                   ← data/mcp_servers.json（同为 {"mcpServers":{…}}
 *                                     形态）拆成逐服务器包装
 *   chatSessions[]                 ← data/sessions*.jsonl 的 SessionV2 记录；
 *                                     memoryEnabled 由 Boolean 转 Int（org.json 的
 *                                     optInt 不做 Boolean→Int 强转）；ISO→毫秒；
 *                                     pinnedAt 三态透传（正毫秒，缺键=未置顶）
 *   chatMessages[]                 ← data/messages*.jsonl 的 MessageV2 记录；
 *                                     parts 数组序列化为 partsJson 字符串；ISO→毫秒
 *
 * 刻意不迁移（两边无对应物，见 DIFF_ANALYSIS）：
 *   CompactMarker/Folder/SubAgent JSONL、chats/<sid>/… 的会话文件树、
 *   shared_files/…、voice_corrections、OpenMinis 的 ConfigRegistry 级标量设置。
 *
 * 加密包支持（minisbak-enc/1，对照 BackupCrypto.kt）：
 *   KEK = PBKDF2-HMAC-SHA256(口令, salt, iterations=600k)
 *   子密钥 = HKDF-SHA256(KEK, info="minisbak/{data,secrets}")
 *   成员 = "MBK1" + 每段 UInt32BE(len) ‖ nonce(12) ‖ ct ‖ tag(16)，AAD=
 *   "<带 .enc 后缀的成员名>#<段号>"（manifest.json 保持明文）。
 */

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object OpenMinisBackupCompat {

    class MinisBakException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** 与 ConfigBackup.MAX_PAYLOAD_BYTES 对齐的输出上限（字符）。 */
    private const val MAX_OUTPUT_CHARS = 64 * 1024 * 1024

    /** RikkaMinis memoryFiles.content 的单文件上限。 */
    private const val MAX_MEMORY_FILE_BYTES = 1L * 1024 * 1024

    /** 防御性分片数上限（64MiB/分片 → 覆盖 ~16GB 的理论包）。记录本身
     *  不设上限——真实输出由 [MAX_OUTPUT_CHARS]（RikkaMinis 导入上限）兜底。 */
    private const val SHARD_LIMIT = 256

    /**
     * [FIX-P1B / RT-S1] 连续多少个分片序号都读不到，才判定「分片序列到此结束」。
     *
     * 为什么必须「连续」而不是「第一个空洞就是终点」：分片序列中间缺一片
     * （世界 W3，文件消失）**不是终点** —— 后面的片还在包里。原实现的
     * `readMember(...) ?: break` 把第一个空洞当终点：`-0002` 缺席就丢掉了
     * `-0003` 及其后的全部消息，4 条静默变 2 条，且不留任何标记。
     *
     * 为什么取 3（容忍最多 2 连缺）：
     *  1. 必须 > 1 —— 取 1 等于把「缺一片」又当成终点，退回原缺陷；
     *  2. 正常包的代价 = 每个流末尾多 2 次 `File.isFile`（共 3 个流 → 6 次
     *     stat），可忽略；
     *  3. 连续 3 片都缺已经不是「一片消失」而是包被截断，继续往后找只会让
     *     一个损坏的包看起来更完整。
     * 末尾那串连续读不到的序号是「序列正常结束」，不是缺片 —— 记录进
     * [JsonlShardRead.missingShards] 的只有「后面还有片被读到」的空洞。
     */
    private const val SHARD_GAP_TOLERANCE = 3

    /** 缺片报告里最多列出多少个成员名（`shardsMissing` 仍是真实总数）。
     *  一个损坏的包可以缺几十片；报告是给人看的，不是转储。 */
    private const val MISSING_SHARD_REPORT_CAP = 32

    private class Keys(val dataKey: ByteArray, val secretsKey: ByteArray)

    /**
     * 嗅探：ZIP 本地文件头 `PK\x03\x04`（PK\x05/\x07 是空/跨段归档头，
     * 正常备份不会以它们开头，一并识别以防万一）。
     */
    fun looksLikeMinisBak(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return b0 == 0x50 && b1 == 0x4B && b3 == 0x04 &&
            (b2 == 0x03 || b2 == 0x05 || b2 == 0x07)
    }

    /**
     * 只流式读一遍 ZIP 里的 `manifest.json`，判断这个包是否加密
     * （`encryption` 段存在 = minisbak-enc/1）。不解包、不碰任何成员，
     * 所以对 16MB 的加密包也是毫秒级。
     *
     * 存在的意义：**在**让用户输口令之前就知道该不该问。加密包没口令直接
     * 调 [convert] 会在第一个成员上抛「需要口令」，那是在用户填完一屏
     * 表单之后才告诉他填错地方了。
     */
    fun requiresPassphrase(packageBytes: ByteArray): Boolean {
        if (!looksLikeMinisBak(packageBytes)) return false
        return try {
            var encrypted = false
            ZipInputStream(packageBytes.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.name == "manifest.json") {
                        encrypted = JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                            .optJSONObject("encryption") != null
                        break
                    }
                    zis.closeEntry()
                }
            }
            encrypted
        } catch (_: Throwable) {
            // 探测失败不阻塞导入：交给 convert() 去报真错。
            false
        }
    }

    /**
     * 把 .minisbak 包字节转成 RikkaMinis ConfigBackup.import 可直接消费的
     * JSON 文档字符串。
     *
     * @param packageBytes 备份文件的原始字节（必须是文件的全部内容，
     *   不能先经过 String 化——ZIP 字节经 UTF-8 解码会丢失信息）
     * @param passphrase 加密包的口令；明文包传 null。加密包传 null 会抛
     *   [MinisBakException]
     * @param includeChats 是否迁移聊天记录（超大包可关掉以缩小输出）
     * @throws MinisBakException 非法包 / 不支持的格式 / 解密失败 / 输出超限
     */
    fun convert(
        packageBytes: ByteArray,
        passphrase: CharArray? = null,
        includeChats: Boolean = true,
    ): String {
        if (!looksLikeMinisBak(packageBytes)) {
            throw MinisBakException("不是 .minisbak ZIP 包")
        }
        val work = Files.createTempDirectory("minisbak-compat").toFile()
        try {
            unzipToDir(packageBytes, work)

            val manifestBytes = readMember(work, null, "manifest.json")
                ?: throw MinisBakException("包内缺少 manifest.json")
            val manifest = runCatching {
                JSONObject(String(manifestBytes, Charsets.UTF_8))
            }.getOrNull() ?: throw MinisBakException("manifest.json 不是合法 JSON")

            // OpenMinis 的 manifest 用 kotlinx.serialization 写出，且
            // BackupFormat.json 没开 encodeDefaults —— `format` 的默认值恰好
            // 就是 "minisbak/1"，所以**真实包里通常根本没有这个键**（实测
            // OpenMinis 1.14 build 28 导出的包：manifest 顶层只有
            // created_at/snapshot_at/app/device_name/backup_id/categories/
            // integrity）。早期这里用 optString("format","") 取值再跟
            // "minisbak/1" 硬比，等于把 100% 的真实包全部拒掉。
            // 现在只在"字段存在且不是 minisbak/1"时拒绝。
            if (manifest.has("format")) {
                val format = manifest.optString("format", "")
                if (format.isNotEmpty() && format != "minisbak/1") {
                    throw MinisBakException(
                        "未知的备份格式 \"$format\"（仅支持 minisbak/1）",
                    )
                }
            }

            val enc = manifest.optJSONObject("encryption")
            val keys = if (enc != null) {
                val kdf = enc.optJSONObject("kdf")
                    ?: throw MinisBakException("加密包缺少 kdf 参数")
                if (passphrase == null || passphrase.isEmpty()) {
                    throw MinisBakException("该备份包已加密，需要口令")
                }
                deriveKeys(passphrase, kdf)
            } else null

            // [FIX-1028B] 三态报告器必须在读任何 section 之前就位：下面每个
            // "存在但类型不对" 的站点都往它记一条，[ShapeNotes.flush] 统一落到
            // 既有的 conversionNotes 通道。
            val notes = ArrayList<String>()
            val shapes = ShapeNotes()

            val secrets: JSONObject? =
                readMember(work, keys, "secrets.json")?.let {
                    runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull()
                        ?: run {
                            // 读到了、但连顶层对象都不是（截断 / 换编码）。原来这里
                            // 直接得到 null：凭据与 env 明文值全部消失，无标记。
                            shapes.custom("secrets-root") { n ->
                                "secrets.json：内容不是 JSON 对象（$n 处），凭据与 env 值未迁移"
                            }
                            null
                        }
                }

            val pcBytes = readMember(work, keys, "data/provider_config.json")
            val pc = if (pcBytes != null) {
                runCatching { JSONObject(String(pcBytes, Charsets.UTF_8)) }.getOrNull()
                    ?: run {
                        shapes.custom("provider-config-root") { n ->
                            "data/provider_config.json：内容不是 JSON 对象（$n 处），provider / 模型 / 组未迁移"
                        }
                        null
                    }
            } else null

            val doc = JSONObject()
                .put("format", "openminis.config.backup")
                .put("version", 1)
                .put("exportedAt", now())
                // [FIX-R3-D / F-13] The converted document never declared this,
                // so `ConfigBackup.import` read `hadSecrets = false` for EVERY
                // .minisbak — including the ones whose credentials had just
                // been carried over from secrets.json. The restore sheet then
                // told the user "this backup contains no API keys, fill them in
                // after restoring" over a package that had them, which is the
                // one warning that trains a user to ignore warnings. The value
                // mirrors what this converter actually carries: env values and
                // provider credentials ride `secrets.json`, so a non-empty
                // secrets object is exactly "credentials included".
                .put("includesSecrets", secrets != null && secrets.length() > 0)

            // [FIX-P1B / RT-S1] 缺片清单：分片序号 → 成员名，喂给缺片报告段。
            // [FIX-1028 / A-5] 同一累计器还收「读到但解析失败」的行数。
            val loss = ShardLoss()
            if (pc != null) {
                val (providers, typeToLabel) = convertProviders(pc, secrets, notes, shapes)
                doc.put("providers", providers)
                val rules = readJsonlShards(work, keys, "data/thinking_rules")
                loss.record("data/thinking_rules", rules)
                doc.put("thinkingRules", convertThinkingRules(rules.records, typeToLabel, shapes))
                doc.put("groups", convertGroups(pc, shapes))
            }
            doc.put("envVars", convertEnvVars(work, keys, secrets, includeSecrets = true, shapes = shapes))
            doc.put("skills", convertSkills(work, keys, notes, shapes, loss))
            doc.put("memoryFiles", convertMemory(work, keys, notes, shapes))
            doc.put("mcpServers", convertMcp(work, keys, shapes))
            val (sessions, messages) = convertChats(work, keys, includeChats, loss, shapes)
            doc.put("chatSessions", sessions)
            doc.put("chatMessages", messages)
            shardLossReport(loss)?.let { doc.put("chatTruncated", it) }
            // [FIX-1028B] 形状不对的站点统一在这里产出（每站点一行，正常包零行）。
            shapes.flush(notes)
            // [FIX-1028 / A-6] The conversion's own warnings — providers carried
            // over with no model entries, skills whose archive failed to pack,
            // memory files over the cap. They were collected into `notes` and
            // then dropped on the floor: no reader anywhere in the tree, so the
            // user's only signal was a shorter restore. Same channel as the
            // shard-loss report (`ImportResult.skipped` via ConfigBackup.import),
            // no new UI.
            if (notes.isNotEmpty()) doc.put("conversionNotes", JSONArray(notes))

            val out = doc.toString()
            if (out.length > MAX_OUTPUT_CHARS) {
                throw MinisBakException(
                    "转换结果 ${out.length} 字符，超过 RikkaMinis 导入上限 " +
                        "$MAX_OUTPUT_CHARS；请用不带聊天记录的备份重试",
                )
            }
            return out
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // ZIP 解包（临时目录，防 zip-slip：拒绝绝对路径与 ".."）
    // ------------------------------------------------------------------

    private fun unzipToDir(bytes: ByteArray, outDir: File) {
        ZipInputStream(bytes.inputStream().buffered()).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                val safe = sanitizeEntryName(e.name) ?: continue
                val target = File(outDir, safe)
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it, 64 * 1024) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun sanitizeEntryName(name: String): String? {
        if (name.isEmpty()) return null
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 读取一个逻辑成员：优先明文名，其次 <name>.enc（解密）。 */
    private fun readMember(work: File, keys: Keys?, name: String): ByteArray? {
        val plain = File(work, name)
        if (plain.isFile) return plain.readBytes()
        val enc = File(work, "$name.enc")
        if (!enc.isFile) return null
        if (keys == null) {
            throw MinisBakException("备份包已加密（$name.enc）：需要口令才能导入")
        }
        val key = if (name == "secrets.json") keys.secretsKey else keys.dataKey
        return decryptMbk1(enc.readBytes(), key, "$name.enc")
    }

    /**
     * 读内容寻址 blob。OpenMinis 的落盘路径是**两级分片**
     * `blobs/<sha[:2]>/<sha256>`（BackupBlobStore.blobFile：
     * `File(File(blobsRoot, digest.take(2)), digest)`），不是扁平的
     * `blobs/<sha>`。分片 fan-out 是为了不让单个目录堆几万个文件。
     * 两种布局都试：分片优先，找不到再退回扁平（更老的包）。
     */
    private fun readBlob(work: File, keys: Keys?, sha: String): ByteArray? {
        if (sha.length < 2) return null
        return readMember(work, keys, "blobs/${sha.take(2)}/$sha")
            ?: readMember(work, keys, "blobs/$sha")
    }

    // ------------------------------------------------------------------
    // minisbak-enc/1 解密
    // ------------------------------------------------------------------

    /**
     * [FIX-1028B / A-13] 包里是否存在任何 blob 内容。只探两层
     * （`blobs/<xx>/<sha>` 与扁平 `blobs/<sha>`），不递归 —— 用来区分「这个包
     * 本来就没带文件」与「索引丢了但内容还在」。
     */
    private fun hasAnyBlob(work: File): Boolean {
        val root = File(work, "blobs")
        if (!root.isDirectory) return false
        return root.listFiles()?.any { it.isFile || it.listFiles()?.isNotEmpty() == true } == true
    }

    private fun deriveKeys(passphrase: CharArray, kdf: JSONObject): Keys {
        val alg = kdf.optString("alg", "pbkdf2-hmac-sha256")
        if (alg != "pbkdf2-hmac-sha256") {
            throw MinisBakException("不支持的 KDF 算法：$alg")
        }
        val salt = Base64.getDecoder().decode(kdf.optString("salt", ""))
        val iterations = if (kdf.has("iterations")) kdf.optInt("iterations") else 600_000
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val kek = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        // [fix-minisbak-hygiene] KEK 派生完即清口令副本（对齐上游 BackupCrypto
        // 的 clearPassword），避免口令在堆上多留整个解密周期。
        spec.clearPassword()
        return Keys(hkdfSha256(kek, "minisbak/data"), hkdfSha256(kek, "minisbak/secrets"))
    }

    /** RFC 5869 单块展开（32 字节输出），空盐 = 32 个零字节。 */
    private fun hkdfSha256(ikm: ByteArray, info: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun decryptMbk1(raw: ByteArray, key: ByteArray, aadPath: String): ByteArray {
        if (raw.size < 4 || String(raw, 0, 4, Charsets.US_ASCII) != "MBK1") {
            throw MinisBakException("加密成员损坏（无 MBK1 魔数）：$aadPath")
        }
        val out = ByteArrayOutputStream()
        var i = 4
        var segment = 0
        while (i < raw.size) {
            if (i + 4 > raw.size) throw MinisBakException("加密成员损坏：$aadPath")
            val len = ((raw[i].toInt() and 0xFF) shl 24) or
                ((raw[i + 1].toInt() and 0xFF) shl 16) or
                ((raw[i + 2].toInt() and 0xFF) shl 8) or
                (raw[i + 3].toInt() and 0xFF)
            i += 4
            // [fix-minisbak-compat] 段长下限 = 12 字节 nonce + 16 字节 GCM tag。
            // 原先只查上界，len < 12 时 copyOfRange 的 from > to 会抛裸
            // IllegalArgumentException（而非 MinisBakException），错误信息
            // 绕开统一的"加密成员损坏"语义。
            // [fix-minisbak-overflow] 上界用减法：len 是 4B 大端 Int，i ≥ 1 时
            // i + len 可环绕为负 → 上界失效 → copyOfRange 抛裸 IAE。
            if (len < 12 + 16 || len > raw.size - i) {
                throw MinisBakException("加密成员损坏：$aadPath")
            }
            val nonce = raw.copyOfRange(i, i + 12)
            val body = raw.copyOfRange(i + 12, i + len)
            i += len
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD("$aadPath#$segment".toByteArray(Charsets.UTF_8))
            try {
                out.write(cipher.doFinal(body))
            } catch (e: Exception) {
                throw MinisBakException("解密失败（口令错误或文件损坏）：$aadPath", e)
            }
            segment++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 分片 JSONL 读取：base、base-0002、base-0003…（BackupJsonlWriter 规则）
    // ------------------------------------------------------------------

    /**
     * [FIX-P1B / RT-S1] 一次分片 JSONL 读取的结果：记录 + **真实缺片**。
     *
     * [missingShards] 只含「洞」（1 基序号，且后面还有片被读到）。序列末尾那串
     * 读不到的序号是「正常结束」——若把末尾探测也记成缺片，每个正常包都会被
     * 报成缺 [SHARD_GAP_TOLERANCE] - 1 片。
     */
    internal data class JsonlShardRead(
        val records: List<JSONObject>,
        val missingShards: List<Int>,
        /**
         * [FIX-1028 / A-5] Non-blank lines that were present but did not parse
         * as a JSON object (a truncated record, a transfer that cut mid-line,
         * a half-written shard).
         *
         * Counted, not just dropped: before this the loop's `runCatching`
         * discarded the line and said nothing, so the same "restored fine"
         * screen covered both a complete package and one that lost messages to
         * a damaged line. Reuses the existing [chatTruncated] channel (see
         * [shardLossReport]) instead of inventing a second one.
         */
        val unparsableLines: Int = 0,
    )

    /** 分片成员名（第 1 片无序号后缀，第 2 片起 `-0002`…）。构造端与读取端
     *  共用这一个函数，缺片报告里的名字才和真实成员名一致。 */
    private fun shardMemberName(base: String, shard: Int): String =
        if (shard == 1) "$base.jsonl"
        else String.format(Locale.US, "%s-%04d.jsonl", base, shard)

    private fun readJsonlShards(
        work: File,
        keys: Keys?,
        base: String,
        limit: Int = SHARD_LIMIT,
    ): JsonlShardRead {
        val records = ArrayList<JSONObject>()
        val missing = ArrayList<Int>()
        var unparsable = 0
        // 还没被「后面的命中」兑现的空洞：只有在读到后续片时才升级成缺片。
        val pendingHoles = ArrayList<Int>()
        var shard = 1
        while (shard <= limit) {
            val bytes = readMember(work, keys, shardMemberName(base, shard))
            if (bytes == null) {
                pendingHoles.add(shard)
                if (pendingHoles.size >= SHARD_GAP_TOLERANCE) break
                shard++
                continue
            }
            missing.addAll(pendingHoles)
            pendingHoles.clear()
            val text = String(bytes, Charsets.UTF_8)
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                // [FIX-1028 / A-5] A line that does not parse is a message (or a
                // session, or a thinking rule) that will never appear — count it
                // so the import can say so instead of reporting a clean restore.
                val rec = runCatching { JSONObject(t) }.getOrNull()
                if (rec == null) unparsable++ else records.add(rec)
            }
            shard++
        }
        return JsonlShardRead(records, missing, unparsable)
    }

    /** [FIX-P1B / RT-S1] 缺片序号 → 成员名，追加进调用方的总清单。 */
    private fun recordMissingShards(base: String, read: JsonlShardRead): List<String> =
        read.missingShards.map { shardMemberName(base, it) }

    /** [FIX-1028 / A-5] 一次转换里「包不完整」的两种证据的累计器：
     *  [missing] 收缺片成员名，[unparsableLines] 收读到了但解析失败的行。
     *  两者最后都进 [shardLossReport]，挂在同一条 `chatTruncated` 通道上。 */
    private class ShardLoss {
        val missing = ArrayList<String>()
        var unparsableLines = 0

        fun record(base: String, read: JsonlShardRead) {
            missing.addAll(recordMissingShards(base, read))
            unparsableLines += read.unparsableLines
        }
    }

    /**
     * [FIX-P1B / RT-S1] 缺片报告段。
     *
     * 通道选择：挂在既有 `chatTruncated` 段上 —— 它是导入链路里**唯一**被读的
     * 「聊天内容不全」通道（`ConfigBackup.import` 读 `sessionsDropped` /
     * `messagesDropped` 并写进 `ImportResult.skipped`）。本段新增的键对现存
     * 读取方是无害的（`optInt(key, 0)` 默认 0 ⇒ 行为不变），而把「缺了几片、
     * 缺的是哪几片」放在同一个段里，让「让用户看到 N 片缺失」只差一次
     * `optInt` 读 —— 那个读在本支的禁区（`ConfigBackup.kt` 由 P1-A 同时施工），
     * 已作为交接项写进 `fix-reports/FIX-P1B-BACKUP-WRITE-READ.md`。
     *
     * 为什么必须报告而不是只 Log：缺片 = 消息永久丢失（源设备那份还在，
     * 但用户在本机看到的是一个「导入成功」的短历史）。沉默的部分恢复比
     * 明确报错更危险。
     *
     * @return null 表示没有任何缺片（正常包 ⇒ 文档形状与升级前逐键一致）。
     */
    private fun shardLossReport(loss: ShardLoss): JSONObject? {
        if (loss.missing.isEmpty() && loss.unparsableLines == 0) return null
        val missing = loss.missing
        return JSONObject().apply {
            if (missing.isNotEmpty()) {
                put("shardsMissing", missing.size)
                put("missingShards", JSONArray(missing.take(MISSING_SHARD_REPORT_CAP)))
                put("sessionsShardsMissing", missing.count { it.startsWith("data/sessions") })
                put("messagesShardsMissing", missing.count { it.startsWith("data/messages") })
                put("thinkingRulesShardsMissing", missing.count { it.startsWith("data/thinking_rules") })
            }
            // [FIX-1028 / A-5] Separate key, separate meaning: a shard that was
            // READ but held a line the parser rejected. Independent of missing
            // shards (both can be 0/1 in any combination), so it gets its own
            // count instead of being folded into shardsMissing.
            if (loss.unparsableLines > 0) put("unparsableLines", loss.unparsableLines)
        }
    }

    // ------------------------------------------------------------------
    // ISO-8601 → epoch 毫秒（兼容裸 Long 的历史格式）
    // ------------------------------------------------------------------

    internal fun isoToMillis(v: Any?): Long? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Long) return v
        if (v is Number) return v.toLong()
        val s = v.toString().trim()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it }
        // [fix-minisbak-compat] OpenMinis 自家的 BackupRecordMapper.millis()
        // 接受四种 ISO-8601 形态（有无毫秒小数 × 'Z'/时区偏移）；而
        // kotlinx.serialization 的 Instant 在毫秒非零时序列化成
        // "…T…SSS…Z"——真实包的时间戳几乎必然带小数位。原先只认整秒形态，
        // 命不中就把 createdAt/updatedAt 静默归零、provider 落到 now()，
        // 整条时间线在导入后失真。java.time 的 OffsetDateTime 一次覆盖
        // 全部四种（含 3~9 位小数），minSdk 26 且项目其他处已用 java.time。
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    // ------------------------------------------------------------------
    // 「读到了但形状不对」的第三态
    // ------------------------------------------------------------------

    /**
     * [FIX-1028B / W8-第三态] 一次转换内的「形状不对」报告器。
     *
     * 本层对「读不到」已经建了三套墙（[JsonlShardRead.missingShards] /
     * `skipped` / [MinisBakException]），**对「读到了但形状不对」一套都没有**：
     * `optJSONArray(k) == null` 同时表示两种完全不同的世界 —— 「对端没有这个
     * section」（正常的旧包 / 未启用的分类）与「对端有，但类型换了」（上游格式
     * 换代）。原实现一律按前者静默当成没有，于是：
     *
     *  - `parts` 变成字符串 → 消息历史成空壳（条数对得上、点进去是空的）；
     *  - `instances` 变成对象 → provider / 模型整体消失；
     *  - `sortOrder` 缺键落 0 → 下游为缺键准备的下标兜底永远走不到。
     *
     * 三种情形的共同点是**计数与界面都正常**：用户看到的是一个更短的恢复，
     * 没有任何解释。所以第三态必须产出一条能定位的 note（站点 + 键 + 期望类型
     * + 实际类型），走既有的 `conversionNotes` 通道（
     * `ConfigBackup.import` 会把它 addAll 进 `ImportResult.skipped`）。
     *
     * 为什么按站点折叠：同一站点能命中上千次（每条消息都可能缺 `sortOrder`），
     * 逐条产出会把 `conversionNotes` 灌成日志。这里只计数，[flush] 时每个站点
     * 产出一行。**正常包一个站点都不命中 ⇒ 不产出任何行 ⇒ 输出文档与升级前
     * 逐字节相同**（这条不变量由 `OpenMinisBackupCompatTest` 的对照臂钉住）。
     */
    private class ShapeNotes {
        private class Hit(var count: Int, val build: (Int) -> String)

        private val hits = LinkedHashMap<String, Hit>()

        /** 「存在但类型不对」。[where] 要能定位到成员/section，[key] 是那个键。 */
        fun wrong(where: String, key: String, expected: String, actual: String) {
            on("wrong\u0000$where\u0000$key\u0000$expected\u0000$actual") { n ->
                val suffix = if (n > 1) "（同一站点共 $n 处）" else ""
                "$where 的 \"$key\"：期望 $expected，实际 $actual；已按「没有」处理$suffix"
            }
        }

        /** 「缺键」——不是类型错误，但同样会让下游的兜底失效（A-20）。 */
        fun missing(where: String, key: String) {
            on("missing\u0000$where\u0000$key") { n ->
                val suffix = if (n > 1) "（共 $n 条记录）" else ""
                "$where 缺 \"$key\"：已省略该键，交由导入端的下标兜底$suffix"
            }
        }

        /** 自定义文案（同一站点折叠，[build] 收到该站点的命中次数）。 */
        fun custom(id: String, build: (Int) -> String) {
            on(id, build)
        }

        private fun on(id: String, build: (Int) -> String) {
            val h = hits[id]
            if (h == null) hits[id] = Hit(1, build) else h.count++
        }

        fun flush(into: MutableList<String>) {
            for (h in hits.values) into.add(h.build(h.count))
        }
    }

    /** JSON 值的类型名（note 里的「实际类型」）。`JSONObject.NULL` 是单例对象，
     *  必须先判掉，否则它的 `toString()` 会伪装成正常值。 */
    private fun jsonTypeName(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> v.javaClass.simpleName
    }

    /**
     * 读一个「应该是数组」的键，三态分开：
     *  - 缺失 / 显式 null ⇒ [fallback]（与 `optJSONArray(k) ?: fallback` 一致）；
     *  - 是数组 ⇒ 原数组（与原来逐字节一致）；
     *  - 存在但不是数组 ⇒ [fallback] + 一条 note。
     */
    private fun ShapeNotes.array(
        source: JSONObject,
        key: String,
        where: String,
        fallback: JSONArray = JSONArray(),
    ): JSONArray = arrayOrNull(source, key, where) ?: fallback

    /** 同 [array]，但「缺失」也返回 null（`optJSONArray(k)?.let { … }` 的语义）。 */
    private fun ShapeNotes.arrayOrNull(
        source: JSONObject,
        key: String,
        where: String,
    ): JSONArray? {
        val v = source.opt(key)
        if (v == null || v == JSONObject.NULL) return null
        if (v is JSONArray) return v
        wrong(where, key, "array", jsonTypeName(v))
        return null
    }

    /** 读一个「应该是对象」的键（`optJSONObject(k)` 的第三态版本）。 */
    private fun ShapeNotes.objOrNull(
        source: JSONObject,
        key: String,
        where: String,
    ): JSONObject? {
        val v = source.opt(key)
        if (v == null || v == JSONObject.NULL) return null
        if (v is JSONObject) return v
        wrong(where, key, "object", jsonTypeName(v))
        return null
    }

    /**
     * [FIX-1028B / A-20] `sortOrder` 的三态：数字 / 缺键 / 存在但不是数字。
     *
     * 原实现无条件写 `optInt("sortOrder", 0)`：缺键落 0，于是**下游为缺键准备的
     * 下标兜底永远走不到**（`ConfigBackup` 的 `optInt("sortOrder", i)`、
     * `ProviderRepository.restoreThinkingRules` 的同款），一整批消息 / 规则带上
     * 同一个 0，顺序塌成"谁先被插入谁靠前"。
     *
     * 修法 = 缺键就**不写该键**，让下游的 `i` 兜底接住；类型不对同样不写并记
     * 一条 note。正常包（每条都带 sortOrder）输出逐字节不变。
     *
     * @return null 表示不该写这个键（缺键或类型不对）。
     */
    private fun ShapeNotes.sortOrder(source: JSONObject, where: String): Int? {
        val v = source.opt("sortOrder")
        if (v == null || v == JSONObject.NULL) {
            missing(where, "sortOrder")
            return null
        }
        val n: Int? = when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
        if (n != null) return n
        wrong(where, "sortOrder", "number", jsonTypeName(v))
        return null
    }

    // ------------------------------------------------------------------
    // 各类别转换
    // ------------------------------------------------------------------

    private fun convertProviders(
        pc: JSONObject,
        secrets: JSONObject?,
        notes: MutableList<String>,
        shapes: ShapeNotes,
    ): Pair<JSONArray, HashMap<String, Pair<String, String>>> {
        // [FIX-1028B / A-1] 「对端没有这个 section」（正常旧包 / 未启用分类 ⇒ 空）
        // 与「对端有但类型变了」（上游换代 ⇒ provider 整体消失）必须分开报。
        val instances = shapes.array(pc, "instances", "data/provider_config.json")
        val entries = shapes.array(pc, "modelEntries", "data/provider_config.json")
        val secretByInstance = HashMap<String, JSONObject>()
        if (secrets != null) {
            val arr = shapes.arrayOrNull(secrets, "providers", "secrets.json")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val id = s.optString("instanceId", "")
                    if (id.isNotEmpty()) secretByInstance[id] = s
                }
            }
        }
        val typeToLabel = HashMap<String, Pair<String, String>>()

        val out = JSONArray()
        for (i in 0 until instances.length()) {
            val inst = instances.optJSONObject(i) ?: continue
            val ptype = inst.optString("providerType", "")
            val label = inst.optString("label", "")
            if (ptype.isEmpty() || label.isEmpty()) continue
            typeToLabel[inst.optString("id", "")] = ptype to label

            val obj = JSONObject()
            obj.put("providerType", ptype)
            obj.put("label", label)
            obj.put("credentialType", inst.optString("credentialType", "apiKey"))
            obj.put("createdAt", isoToMillis(inst.opt("createdAt")) ?: now())
            inst.optString("customBaseURL", "").takeIf { it.isNotEmpty() }
                ?.let { obj.put("customBaseURL", it) }
            // [fix-minisbak-append-v1-parity] appendV1Suffix 两端默认都是 true：
            // RikkaMinis 导入端 optBoolean("appendV1Suffix", true)，Android 自家
            // 导出只在 false 时写键（ProviderRepository exportInstanceJSON 的
            // `if (!instance.appendV1Suffix) put(…, false)`——缺键即默认 true）。
            // 原实现照抄了隔壁 useResponsesAPI 的「默认 false、只在 true 时写」
            // 形态：那行对 useResponsesAPI 是对的（它的导入默认恰是 false），但
            // 套在极性相反的字段上 → iOS 显式 false 的实例丢键后被导回 true，
            // 每个请求平白多出 /v1 后缀（非 /v1 网关 404）。改三态透传：显式值
            // 原样传递；缺键保持既有行为（导入端默认）。iOS 侧「缺键」的自身
            // 默认值无法从本仓库证实（上游闭源），透传在任何语义下都严格不劣。
            if (inst.has("appendV1Suffix")) {
                obj.put("appendV1Suffix", inst.optBoolean("appendV1Suffix"))
            }
            if (inst.optBoolean("useResponsesAPI", false)) obj.put("useResponsesAPI", true)
            inst.optString("customUserAgent", "").takeIf { it.isNotEmpty() }
                ?.let { obj.put("customUserAgent", it) }
            if (inst.has("isEnabled")) obj.put("isEnabled", inst.optBoolean("isEnabled"))
            if (inst.has("azureMode")) obj.put("azureMode", inst.optBoolean("azureMode"))
            if (inst.has("pinned")) obj.put("pinned", inst.optBoolean("pinned"))
            if (inst.has("imageEndpointMode")) {
                obj.put("imageEndpointMode", inst.optString("imageEndpointMode"))
            }
            if (inst.has("imageEndpointResolved")) {
                obj.put("imageEndpointResolved", inst.optString("imageEndpointResolved"))
            }

            secretByInstance[inst.optString("id", "")]?.let { sec ->
                // OpenMinis secrets.json 的值本身就是 base64 —— RikkaMinis 的
                // importInstanceCredentials 用 Base64.decode + 明文回退读取，
                // 原样透传即可。
                sec.optString("apiKey", "").takeIf { it.isNotEmpty() }
                    ?.let { obj.put("apiKey", it) }
                sec.optString("manualOAuthToken", "").takeIf { it.isNotEmpty() }
                    ?.let { obj.put("manualOAuthToken", it) }
                sec.optString("oauthToken", "").takeIf { it.isNotEmpty() }
                    ?.let { obj.put("oauthToken", it) }
                sec.optString("oauthEmail", "").takeIf { it.isNotEmpty() }
                    ?.let { obj.put("oauthEmail", it) }
                sec.optString("oauthGcpProject", "").takeIf { it.isNotEmpty() }
                    ?.let { obj.put("oauthGcpProject", it) }
            }

            val models = JSONArray()
            val entryIds = JSONArray()
            for (j in 0 until entries.length()) {
                val e = entries.optJSONObject(j) ?: continue
                if (e.optString("providerInstanceId", "") != inst.optString("id", "")) continue
                val model = e.optJSONObject("model") ?: continue
                val mo = JSONObject()
                mo.put("modelId", model.optString("id", ""))
                mo.put("displayName", model.optString("displayName", model.optString("id", "")))
                mo.put("isHidden", e.optBoolean("isHidden", false))
                if (e.optBoolean("isCustom", false)) mo.put("isCustom", true)
                if (model.optInt("contextWindow", 0) > 0) {
                    mo.put("contextWindow", model.optInt("contextWindow"))
                }
                if (model.optInt("maxOutputTokens", 0) > 0) {
                    mo.put("maxOutputTokens", model.optInt("maxOutputTokens"))
                }
                if (model.has("supportsReasoning")) {
                    mo.put("supportsReasoning", model.optBoolean("supportsReasoning"))
                }
                model.optString("interleavedReasoningField", "").takeIf { it.isNotEmpty() }
                    ?.let { mo.put("interleavedReasoningField", it) }
                // [fix-minisbak-compat-parity] 1021 修好了 Android 自家导出/导入的
                // 这对字段（provider_import_id_roundtrip_check 第 2/3 臂），但
                // OpenMinis 转换层没跟进：iOS LLMModel 本就携带两者（LLMModel.kt
                // 注释 "Mirrors iOS LLMModel.reasoningEffortValues /
                // declaresNoEffortTiers"）。漏迁后跨端恢复静默蒸发 effort 档位
                // 上限（ThinkingLevelCatalog）与「无 effort 档位」声明（xAI 系模型
                // 不该收 reasoning_effort），且只对 models.dev 里查不到的网关模型
                // 生效——正是最需要手工档位配置的那批。写侧守卫镜像 Android 导出：
                // 空数组不写；三态标志保持「显式 null = 缺省」。
                // [FIX-1028B / A-1] 模态/档位数组换代（数组 → 其它类型）时原来
                // 被静默当成没有：effort 档位与输入/输出模态直接蒸发，无 note。
                shapes.arrayOrNull(model, "reasoningEffortValues", "data/provider_config.json 的 model")?.let {
                    if (it.length() > 0) mo.put("reasoningEffortValues", it)
                }
                if (model.has("declaresNoEffortTiers") && !model.isNull("declaresNoEffortTiers")) {
                    mo.put("declaresNoEffortTiers", model.optBoolean("declaresNoEffortTiers"))
                }
                isoToMillis(e.opt("userModifiedAt"))?.let { mo.put("userModifiedAt", it) }
                // [fix-minisbak-compat-parity] costTier 驱动 cheapestFirst 路由；
                // Android 导出/导入两端都带（optInt + Int.MIN_VALUE 哨兵，0 值 =
                // 免费档位必须存活）。上游 iOS 是否导出该键无法从本仓库证实
                // （上游闭源；该字段源自本仓 [T-recovery] 特性）——守卫透传：
                // 源缺键 → 输出缺键（与现状完全一致），源有键（携带该字段的其他
                // Minis 系导出器）→ 迁移。
                if (e.has("costTier") && !e.isNull("costTier")) {
                    mo.put("costTier", e.optInt("costTier"))
                }
                shapes.arrayOrNull(model, "inputModalities", "data/provider_config.json 的 model")?.let {
                    if (it.length() > 0) mo.put("inputModalities", it)
                }
                shapes.arrayOrNull(model, "outputModalities", "data/provider_config.json 的 model")?.let {
                    if (it.length() > 0) mo.put("outputModalities", it)
                }
                if (model.optInt("modalityOverride", 0) != 0) {
                    mo.put("modalityOverride", model.optInt("modalityOverride"))
                }
                e.optJSONObject("overrides")?.let { ov ->
                    val o = JSONObject()
                    for (k in arrayOf(
                        "displayName", "maxOutputTokens", "contextWindow",
                        "supportsReasoning", "inputModalities", "outputModalities",
                        "maxThinkingLevel", "modalityOverride",
                    )) {
                        if (ov.has(k) && !ov.isNull(k)) o.put(k, ov.get(k))
                    }
                    if (o.length() > 0) mo.put("overrides", o)
                }
                models.put(mo)
                entryIds.put(e.optString("uuid", ""))
            }
            obj.put("models", models)
            obj.put("_entryIds", entryIds)
            if (models.length() == 0) {
                // 与 Python 版一致：0 模型也导出（凭据/实例仍有价值，
                // RikkaMinis 导入端对空 models 保留内置模型表）。
                notes.add("provider \"$label\"：没有模型条目，仍按 0 模型导出")
            }
            out.put(obj)
        }
        return out to typeToLabel
    }

    private fun convertThinkingRules(
        records: List<JSONObject>,
        typeToLabel: Map<String, Pair<String, String>>,
        shapes: ShapeNotes,
    ): JSONArray {
        val byInstance = LinkedHashMap<String, JSONArray>()
        for (rec in records) {
            if (rec.optString("t", "") != "ThinkingRuleV1") continue
            // [FIX-1028B] d 不是对象 = 这条规则读不出来。原来 `?: continue` 把
            // 它当成「没有这条记录」——规则少了几条，界面上看不出任何异常。
            val d = shapes.objOrNull(rec, "d", "data/thinking_rules.jsonl 的 ThinkingRuleV1")
                ?: continue
            val instId = d.optString("instanceId", "")
            val arr = byInstance.getOrPut(instId) { JSONArray() }
            val r = JSONObject()
            r.put("id", d.optString("id", ""))
            r.put("label", d.optString("label", ""))
            r.put("scopeKind", d.optString("scopeKind", "allModels"))
            d.optString("scopePattern", "").takeIf { it.isNotEmpty() }
                ?.let { r.put("scopePattern", it) }
            r.put("wireFormatJson", d.optString("wireFormatJson", "{}"))
            // [T-fix-import-id-roundtrip] Carry the reasoning-echo policy across
            // the OpenMinis conversion. The restore side (restoreThinkingRules)
            // already reads "reasoningEchoJson" — only this converter dropped it,
            // so a cross-device restore silently reset the echo policy to null.
            // Key name matches ThinkingRuleCoding.encodeEcho's canonical wire
            // key. Additive + optional: records without the key stay null.
            d.optString("reasoningEchoJson", "").takeIf { it.isNotEmpty() }
                ?.let { r.put("reasoningEchoJson", it) }
            // [FIX-1028B / A-20] 缺键不再写成 0：见 [sortOrder] 的说明。
            shapes.sortOrder(d, "data/thinking_rules.jsonl 的 ThinkingRuleV1")?.let {
                r.put("sortOrder", it)
            }
            arr.put(r)
        }
        val out = JSONArray()
        for ((instId, rules) in byInstance) {
            val pair = typeToLabel[instId] ?: continue
            out.put(
                JSONObject()
                    .put("providerType", pair.first)
                    .put("providerLabel", pair.second)
                    .put("rules", rules),
            )
        }
        return out
    }

    private fun convertGroups(pc: JSONObject, shapes: ShapeNotes): JSONArray {
        // [FIX-1028B / A-9] 同 instances：组容器换代时整段消失且无声。
        val groups = shapes.arrayOrNull(pc, "modelGroups", "data/provider_config.json")
            ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val obj = JSONObject()
            obj.put("id", g.optString("id", ""))
            obj.put("name", g.optString("name", ""))
            // 成员表换代 ⇒ 组还在、成员全丢，界面上是一个空组。
            obj.put(
                "memberEntryIds",
                shapes.array(g, "memberEntryIds", "data/provider_config.json 的 modelGroups[]"),
            )
            obj.put("strategy", g.optString("strategy", "fallback"))
            obj.put("fallbackStrategy", g.optString("fallbackStrategy", "default"))
            g.optString("defaultThinkingLevel", "").takeIf { it.isNotEmpty() }
                ?.let { obj.put("defaultThinkingLevel", it) }
            if (g.optInt("contextLimitTokens", 0) > 0) {
                obj.put("contextLimitTokens", g.optInt("contextLimitTokens"))
            }
            if (g.optInt("lastContextLimitTokens", 0) > 0) {
                obj.put("lastContextLimitTokens", g.optInt("lastContextLimitTokens"))
            }
            out.put(obj)
        }
        return out
    }

    private fun convertEnvVars(
        work: File,
        keys: Keys?,
        secrets: JSONObject?,
        includeSecrets: Boolean,
        shapes: ShapeNotes,
    ): JSONArray {
        val out = JSONArray()
        val raw = readMember(work, keys, "data/env_vars.json") ?: return out
        // OpenMinis 的 data/env_vars.json 是**顶层数组**
        // （[{id,key,note,createdAt},…]），没有 wrapper 对象；老包可能是
        // {"entries":[…]}。早先这里无条件 JSONObject(text) → 抛异常 →
        // getOrNull() → null → ?: return out，把 29 条环境变量静默丢成 0 条。
        //
        // [FIX-1028B / A-11] 三种「读到了但形状不对」现在分开报：顶层数组解析
        // 失败 / 既不是数组也不是对象 / wrapper 的 entries 不是数组。原来三者
        // 与「对端本来就没有环境变量」产生的是同一个结果（0 条 + 无声）。
        val text = String(raw, Charsets.UTF_8).trim()
        val arr: JSONArray = if (text.startsWith("[")) {
            runCatching { JSONArray(text) }.getOrNull() ?: run {
                shapes.custom("env-vars-array") { n ->
                    "data/env_vars.json：顶层数组无法解析（$n 处），环境变量未迁移"
                }
                JSONArray()
            }
        } else {
            val wrapper = runCatching { JSONObject(text) }.getOrNull()
            if (wrapper == null) {
                shapes.custom("env-vars-root") { n ->
                    "data/env_vars.json：既不是数组也不是 JSON 对象（$n 处），环境变量未迁移"
                }
                JSONArray()
            } else {
                // entries 缺失 = 老包 / 空（正常的"没有"）；类型不对 = 换代。
                shapes.array(wrapper, "entries", "data/env_vars.json")
            }
        }
        val secretValues = HashMap<String, String>()
        if (includeSecrets && secrets != null) {
            val sarr = shapes.arrayOrNull(secrets, "envVars", "secrets.json")
            if (sarr != null) {
                for (i in 0 until sarr.length()) {
                    val s = sarr.optJSONObject(i) ?: continue
                    val name = s.optString("name", "")
                    if (name.isEmpty()) continue
                    secretValues[name] = runCatching {
                        String(Base64.getDecoder().decode(s.optString("value", "")))
                    }.getOrDefault("")
                }
            }
        }
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val key = e.optString("key", "")
            if (key.isEmpty()) continue
            out.put(
                JSONObject()
                    .put("key", key)
                    .put("note", e.optString("note", ""))
                    .put("group", e.optString("group", ""))
                    // RikkaMinis 的 envVars.value 是明文（无 base64 语义）
                    .put("value", if (includeSecrets) secretValues[key] ?: "" else ""),
            )
        }
        return out
    }

    private fun convertSkills(
        work: File,
        keys: Keys?,
        notes: MutableList<String>,
        shapes: ShapeNotes,
        loss: ShardLoss,
    ): JSONArray {
        val out = JSONArray()
        // [FIX-1028B / A-13] files.index 也是 JSONL 流 —— 与 data/sessions 等一样
        // 可能被 BackupJsonlWriter 分片（base、base-0002…）。原实现只 readMember
        // 第一片：分片包里第 2 片起的索引全部丢掉，那些技能文件在重组时"不存在"。
        // 顺带：索引整段缺失（包里有 blob 内容但没有索引）原来连一条 note 都没有。
        val indexRead = readJsonlShards(work, keys, "files.index")
        loss.record("files.index", indexRead)
        val records = indexRead.records
        if (records.isEmpty()) {
            if (hasAnyBlob(work)) {
                shapes.custom("files-index-missing") { n ->
                    "包内有 blob 内容但没有可读的 files.index.jsonl（$n 处）：skills 无法重组"
                }
            }
            return out
        }
        val bySkill = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (e in records) {
            val path = e.optString("path", "")
            if (!path.startsWith("skills/")) continue
            if (e.optBoolean("isDirectory", false)) continue
            if (e.optString("skipped", "").isNotEmpty()) continue
            val sha = e.optString("sha256", "")
            if (sha.isEmpty()) continue
            val parts = path.split('/')
            if (parts.size < 3) continue
            val skillId = parts[1]
            val rel = parts.drop(2).joinToString("/")
            bySkill.getOrPut(skillId) { ArrayList() }.add(rel to sha)
        }
        for ((skillId, files) in bySkill) {
            try {
                var missingBlobs = 0
                val zipBytes = ByteArrayOutputStream().use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        // STORED：OpenMinis 的包本身不压缩，这里保持一致
                        zos.setMethod(ZipOutputStream.STORED)
                        for ((rel, sha) in files.sortedBy { it.first }) {
                            val blob = readBlob(work, keys, sha)
                            if (blob == null) {
                                // [FIX-1028B] 索引里有、blob 不在：这个文件就从技能
                                // 包里消失（SKILL.md 缺了技能就装不上），原来是纯
                                // `continue`，界面上只看到"技能少了"。
                                missingBlobs++
                                continue
                            }
                            val entry = ZipEntry(rel)
                            entry.size = blob.size.toLong()
                            entry.crc = CRC32().apply { update(blob) }.value
                            zos.putNextEntry(entry)
                            zos.write(blob)
                            zos.closeEntry()
                        }
                    }
                    bos.toByteArray()
                }
                val name = files.firstOrNull { it.first == "SKILL.md" }
                    ?.let { (_, sha) -> readBlob(work, keys, sha) }
                    ?.let { bytes -> parseFrontmatterName(String(bytes, Charsets.UTF_8)) }
                    ?: skillId
                out.put(
                    JSONObject()
                        .put("id", skillId)
                        .put("name", name)
                        .put("archive", Base64.getEncoder().encodeToString(zipBytes)),
                )
                if (missingBlobs > 0) {
                    shapes.custom("skill-missing-blobs\u0000$skillId") { _ ->
                        "skill \"$skillId\"：$missingBlobs 个文件在索引里但 blob 不在包里（该技能不完整）"
                    }
                }
            } catch (t: Throwable) {
                notes.add("skill \"$skillId\"：${t.message ?: "打包失败"}")
            }
        }
        return out
    }

    /** SKILL.md YAML frontmatter 的 `name:` 行。 */
    private fun parseFrontmatterName(md: String): String? {
        val m = Regex("(?m)^name:\\s*(.+)$").find(md) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun convertMemory(
        work: File,
        keys: Keys?,
        notes: MutableList<String>,
        shapes: ShapeNotes,
    ): JSONArray {
        val out = JSONArray()
        val root = File(work, "data/memory")
        if (!root.isDirectory) return out
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            // 加密包会给每个成员追加 .enc 后缀（BackupCrypto/BackupExporter
            // 的就地加密遍历覆盖 data/… 全树）。这里的 f.length() 是密文
            // 尺寸（≈明文+段头+tag），作上限预检足够精确。
            if (f.length() > MAX_MEMORY_FILE_BYTES + 4096) {
                notes.add("memory \"${f.relativeTo(root).path}\"：超过上限跳过")
                return@forEach
            }
            // 统一走 readMember（先试明文名，再试 <name>.enc 解密）——
            // 与 Python 版 direct_files 的语义一致，两种包都覆盖。
            val rel = f.relativeTo(root).path
                .replace(File.separatorChar, '/')
                .removeSuffix(".enc")
            val bytes = readMember(work, keys, "data/memory/$rel")
            if (bytes == null) {
                // [FIX-1028B / A-12] 目录里看得见、读不出来（遍历与读取之间被删 /
                // 被替换，或 .enc 成员读不出来）。原来是纯 `return@forEach`：记忆
                // 文件静默消失，恢复完的 GLOBAL.md / 日报少了一段也没人知道。
                shapes.custom("memory-unreadable\u0000$rel") { _ ->
                    "memory \"$rel\"：包里存在但读不出来，已跳过"
                }
                return@forEach
            }
            out.put(
                JSONObject()
                    // RikkaMinis 的 memory 导入拒绝含路径分隔符的名字 → 拍平
                    // （与 Python 版 minisbak_to_rikka.py 保持一致，用 "__"）
                    .put("name", rel.replace("/", "__").replace("\\", "__"))
                    .put("content", String(bytes, Charsets.UTF_8)),
            )
        }
        return out
    }

    private fun convertMcp(work: File, keys: Keys?, shapes: ShapeNotes): JSONArray {
        val out = JSONArray()
        val bytes = readMember(work, keys, "data/mcp_servers.json") ?: return out
        // [FIX-1028B] 顶层不是对象 / mcpServers 不是对象：两种"读到但形状不对"
        // 原来都折叠成"没有 MCP 服务器"，与正常的空包无法区分。
        val obj = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        if (obj == null) {
            shapes.custom("mcp-root") { n ->
                "data/mcp_servers.json：内容不是 JSON 对象（$n 处），MCP 服务器未迁移"
            }
            return out
        }
        val servers = shapes.objOrNull(obj, "mcpServers", "data/mcp_servers.json")
            ?: return out
        for (id in servers.keys()) {
            val entry = servers.optJSONObject(id) ?: continue
            out.put(JSONObject().put("mcpServers", JSONObject().put(id, entry)))
        }
        return out
    }

    /**
     * [FIX-1028B / A-7] `parts` 的三态，外加「字符串形态」的还原。
     *
     * 原实现 `(d.optJSONArray("parts") ?: JSONArray()).toString()`：类型一变
     * （上游把 parts 序列化成 JSON 文本）就写出 `"[]"` —— 会话列表看得见、
     * 消息条数对得上、每条点进去是空的，而且没有任何标记。这是最贵的一种
     * 静默：用户以为历史被完整恢复，实际只剩空壳。
     *
     * 三种情形：
     *  - 数组 ⇒ 原样返回（与改前逐字节一致，正常包走的就是这条）；
     *  - 字符串且能解析成 JSON 数组 ⇒ **还原内容**。这不是猜测：同一份数据换了
     *    一个编码，能解析就是有内容，丢掉它才是损失（并留一条 note 说明形状）；
     *  - 其它（含解析失败的字符串）⇒ 空数组 + 一条 note。
     */
    private fun partsJsonFor(d: JSONObject, shapes: ShapeNotes): String {
        val v = d.opt("parts")
        if (v is JSONArray) return v.toString()
        if (v == null || v == JSONObject.NULL) return JSONArray().toString()
        if (v is String) {
            val parsed = runCatching { JSONArray(v) }.getOrNull()
            if (parsed != null) {
                shapes.custom("parts-as-json-string") { n ->
                    "chatMessages 的 \"parts\"：$n 条记录以 JSON 字符串形态出现，已解析还原"
                }
                return parsed.toString()
            }
        }
        val actual = jsonTypeName(v)
        shapes.custom("parts-not-array") { n ->
            "chatMessages 的 \"parts\"：期望 array，实际 $actual，$n 条消息按空 parts 导入"
        }
        return JSONArray().toString()
    }

    private fun convertChats(
        work: File,
        keys: Keys?,
        includeChats: Boolean,
        loss: ShardLoss,
        shapes: ShapeNotes,
    ): Pair<JSONArray, JSONArray> {
        val sessionsOut = JSONArray()
        val messagesOut = JSONArray()
        if (!includeChats) return sessionsOut to messagesOut

        val sessionShards = readJsonlShards(work, keys, "data/sessions")
        loss.record("data/sessions", sessionShards)
        for (rec in sessionShards.records) {
            if (rec.optString("t", "") != "SessionV2") continue
            // [FIX-1028B] d 不是对象 = 这条会话读不出来。原来按"没有这条记录"
            // 处理：整个会话连同它的消息引用一起消失，无标记。
            val d = shapes.objOrNull(rec, "d", "data/sessions.jsonl 的 SessionV2")
                ?: continue
            // 新包是 {"session":{…}, …wrapper}；旧包字段平铺 —— 两种都读
            val s = d.optJSONObject("session") ?: d
            val o = JSONObject()
            o.put("id", s.optString("id", ""))
            o.put("title", s.optString("title", ""))
            o.put("modelId", s.optString("modelId", ""))
            o.put("createdAt", isoToMillis(s.opt("createdAt")) ?: 0L)
            o.put("updatedAt", isoToMillis(s.opt("updatedAt")) ?: 0L)
            o.put("category", s.optString("category", ""))
            o.put("lastMessage", s.optString("lastMessage", ""))
            o.put("modelBinding", convertModelBinding(
                d.optString("modelBinding", "").ifEmpty { s.optString("modelBinding", "") },
            ))
            o.put("source", s.optString("source", ""))
            // org.json 的 optInt 不做 Boolean→Int 强转：显式转成 0/1
            val memEnabled = if (d.has("memoryEnabled")) d.optBoolean("memoryEnabled", true)
            else s.optBoolean("memoryEnabled", true)
            o.put("memoryEnabled", if (memEnabled) 1 else 0)
            // [fix-minisbak-compat-parity] 会话置顶时间戳：ConfigBackup 导出写
            // pinnedAt、导入按 has 读取（null=未置顶）。0/负值会被导入端当成
            // 「置顶于纪元零点」，故只写正毫秒值；缺键=不置顶，与现状一致。
            // isoToMillis 同时覆盖上游的裸 Long 与四种 ISO-8601 形态；d/s 双读
            // 对齐 memoryEnabled 的包裹层/平铺层兼容。
            val pinnedRaw = if (d.has("pinnedAt")) d.opt("pinnedAt") else s.opt("pinnedAt")
            isoToMillis(pinnedRaw)?.takeIf { it > 0 }?.let { o.put("pinnedAt", it) }
            o.put("editCount", d.optInt("editCount", 0))
            d.optString("thinkingOverride", "").takeIf { it.isNotEmpty() }
                ?.let { o.put("thinkingOverride", it) }
            if (o.optString("id").isNotEmpty()) sessionsOut.put(o)
        }

        val messageShards = readJsonlShards(work, keys, "data/messages")
        loss.record("data/messages", messageShards)
        for (rec in messageShards.records) {
            if (rec.optString("t", "") != "MessageV2") continue
            val d = shapes.objOrNull(rec, "d", "data/messages.jsonl 的 MessageV2")
                ?: continue
            val o = JSONObject()
            o.put("id", d.optString("id", ""))
            o.put("sessionId", d.optString("sessionId", ""))
            o.put("role", d.optString("role", ""))
            // [FIX-1028B / A-7] parts 的第三态（含字符串还原），见 [partsJsonFor]。
            o.put("partsJson", partsJsonFor(d, shapes))
            o.put("createdAt", isoToMillis(d.opt("createdAt")) ?: 0L)
            // [FIX-1028B / A-20] 缺键不写 0（否则导入端的 `optInt("sortOrder", i)`
            // 下标兜底永远走不到）。理由见 [sortOrder]。
            shapes.sortOrder(d, "data/messages.jsonl 的 MessageV2")?.let {
                o.put("sortOrder", it)
            }
            d.optString("reasoningContent", "").takeIf { it.isNotEmpty() }
                ?.let { o.put("reasoningContent", it) }
            if (o.optString("id").isNotEmpty()) messagesOut.put(o)
        }
        return sessionsOut to messagesOut
    }
}

/**
 * [FIX-R3-D / F-09] Translate a session's `modelBinding` into the JSON shape
 * RikkaMinis persists, or return the input unchanged.
 *
 * OpenMinis stores the binding as a compact string — `"group:<group id>"` or
 * `"entry:<model entry uuid>"` — while every reader on this side
 * (`ChatViewModel.restoreFromBinding`, `remapSessionBindingJson`, the session
 * sheet) expects `{"type":"group","groupId":…}` / `{"type":"entry","entryId":…}`.
 * Passing the compact form through had two consequences, both invisible:
 * `remapSessionBindingJson` did not recognise `optString("type")` and returned
 * it verbatim (so the binding kept a FOREIGN id after a cross-device restore),
 * and `restoreFromBinding` could not parse it, so the session degraded to the
 * stored `modelId` — group routing and the fallback chain silently gone while
 * the session rendered normally.
 *
 * Anything else (already-JSON payloads, an unknown prefix, blank) is returned
 * byte-identically: this is a translation, not a validator. Top-level (not a
 * member) so the real test class can pin it without a package.
 */
internal fun convertModelBinding(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    if (trimmed.startsWith("group:")) {
        val id = trimmed.removePrefix("group:").trim()
        if (id.isEmpty()) return raw
        return JSONObject().put("type", "group").put("groupId", id).toString()
    }
    if (trimmed.startsWith("entry:")) {
        val id = trimmed.removePrefix("entry:").trim()
        if (id.isEmpty()) return raw
        return JSONObject().put("type", "entry").put("entryId", id).toString()
    }
    return raw
}
