package com.rikkaminis.app.provider.thinking

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * T-android-effort-self-learn — 从网关的 400 响应里自举 `reasoning_effort` 真值。
 *
 * ## 为什么需要它
 *
 * 档位知识的两条既有来路都不在这个应用手里：
 *
 *  1. **catalog 声明**（models.dev 一类）——二手真相。实测已证明系统性不准：sensenova
 *     一行 7 个模型全部少声明档位。
 *  2. **[GatewayEffortTruth] 静态表**——四手真相。我们自己发请求测出来、写死在代码里。
 *     这条路的代价是：厂商一改枚举、或出现一个没测过的 host，就必须由**发版**才能吸收。
 *     补丁的发起权在维护者手里，而真正碰到那个网关的人是用户。
 *
 * 但网关在拒绝时会把答案说出口：
 *
 * ```
 * {"error":{"message":"field ReasoningEffort invalid, should be one of:
 *   low, medium, high, xhigh, none"}}
 * ```
 *
 * 这是**唯一的一手真相**——比 catalog 准，比厂商文档准（sensenova 文档写 `max` 是原生档，
 * 实测 400），而且不需要我们事先知道答案。这个类就是那句错误信息的学习器：
 *
 * 400 → 解析出枚举 → 按 `host/model` 落盘 → 下一次请求 clamp 时优先于声明。
 * 第一次仍然失败，第二次就对。
 *
 * ## 第二种拒绝形状：只点名被拒档位
 *
 * 不是每个网关都说全枚举。把 `reasoning_effort` 翻成 Gemini `thinking_level` 的中转
 * 只回一条：`Thinking level is unsupported: THINKING_LEVEL_XHIGH`（2026-11-18 实测）。
 * 那句话同样是一手真相，只是信息更少——只有"这一档不行"。这种情况学到的是一条
 * **上限**（严格弱于被拒档位的那一串），见 [ceilingFromText]。少了这一条，那个 400
 * 什么也学不着，重试会把同一个非法档位原样再发一次。
 *
 * ## 学到的值意味着什么（以及为什么不给它设永久）
 *
 * 学到的是**已知安全集的下限**，不是能力上限。厂商之后放宽枚举，我们不会知道——
 * 没有 400 就没有新信息，于是会继续把用户 clamp 到旧上限。这是**安全方向的错**
 * （少一个档位，不会炸请求），跟 [GatewayEffortTruth] 的 fallback 行为同向。
 * [TTL_MS] 决定多久愿意重新赌一次。没学到 = 行为完全等于这个类不存在。
 *
 * ## 什么时候会真的学到（诚实评估）
 *
 * 只有"我们发出了一个被拒的值"才学得着。clamp 已经把未知值挡掉，所以触发条件是：
 *
 *  - 声明/静态表**本身不准**，用户选了高档位，我们发了一个自认为合法但网关拒绝的值；
 *  - 用户用 `CustomPath` 规则**主动**发了一个网关不接受的值；
 *  - 厂商**收紧**了枚举（原来接受，现在拒）。
 *
 * 网关静默忽略未知值（不报错）的情况学不到——那本来就没有信息可学。
 *
 * ## 明确不做什么
 *
 *  - **不写 Room。** worker（`:modelservice`）与 host 是不同进程，Room 默认不做多进程写；
 *    `filesDir` 在同一 app 下天然跨进程共享，一次 rename 就够。
 *  - **不改 IPC 协议。** 错误在哪个进程里产生，学习结果就在那个进程里被下一次请求消费；
 *    跨进程靠文件，不靠序列化字段。
 *  - **不主动探测。** 探测要额外消耗 token、可能被限流、而且并非所有网关都在错误里回枚举。
 *    这里只在**已经失败**的请求上学习——信息免费，代价为零。
 *  - **不删/不改静态表。** learned 优先于它，它继续当离线兜底。
 */
object EffortTierLearner {

    private const val TAG = "EffortTierLearner"
    private const val DIR = "learned"
    private const val FILE = "gateway_efforts.json"

    /** 学到的集合多久后过期，愿意再赌一次。 */
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000L

    /**
     * 进程内缓存的失效依据：文件的 (mtime, size) 指纹。
     *
     * 原来是一个 10 秒的盲窗（`now - cachedAt < 10s`），它带来的不只是"多读几
     * 次文件"——**另一个进程写入后，本进程最多 10 秒仍读到旧值**。学到的上限是
     * 在 worker 里写、在 UI 里读的（chat 流的 provider 跑在 `:modelservice`），
     * 于是"网关已经拒了 xhigh"与"写作栏还显示 XHigh"之间必然有一段窗口。
     * 指纹法把这段窗口压到 0：一次 stat 换一次可能的解析，代价可以忽略。
     *
     * 同一毫秒内、且文件大小恰好不变的两次写入会漏掉一次（指纹相同）——概率极低，
     * 且本进程自己的写入会直接置空缓存，所以只可能是"另一进程在同一毫秒写了两版"。
     */
    private fun stampOf(file: File): Long = file.lastModified() * 1_000_003L + file.length()

    /** 从 "one of" 往后扫多少个字符找档位词，足够覆盖一条错误文案。 */
    private const val SCAN_WINDOW = 400

    private const val ONE_OF = "one of"
    private const val KEY_MODEL = "model"

    /**
     * 已知档位词。白名单而非全收：`one of` 之后跟的词不保证都是档位名
     * （"the following: low, medium" 里还有 "the"、"following"）。
     */
    val KNOWN_TIERS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "auto")

    // ── 第二种形状：只点名被拒档位（见 ceilingFromText）──────────────────────────

    /** `THINKING_LEVEL_XHIGH` / `thinkingLevel` / `thinking level` 共有的锚点词。 */
    private const val LEVEL = "level"

    /** `level` 之后找档位词的最大跨度，够覆盖 `level is unsupported: THINKING_LEVEL_x`。 */
    private const val LEVEL_WINDOW = 80

    /**
     * 「不支持」类措辞。文案里必须有其中一种，否则不当作拒绝信号——
     * `Thinking is a strong selling point of this model` 里的档位词不该被学成上限。
     */
    private val UNSUPPORTED_MARKERS = listOf(
        "unsupported", "not supported", "invalid", "unknown", "unrecognized", "illegal",
    )

    /** `level` 与档位词之间可能夹着的功能词（`level is unsupported: ...`）。 */
    private val LEVEL_FILLERS = setOf(
        "is", "was", "the", "a", "an", "of", "for", "this", "that", "model", "value",
        "level", "levels", "set", "to", "as", "with", "not",
    )

    /**
     * 锚点与列表之间可能夹着的连接词（`one of the following values: ...`）。
     * 只收这类功能词，不做"允许跳过 N 个任意词"的宽松匹配——那会重新打开
     * `one of a high traffic month` 这类散文误学。
     */
    private val LIST_FILLERS = setOf(
        "the", "following", "these", "values", "value", "options", "option",
        "allowed", "permitted", "supported", "acceptable", "valid", "possible",
        "one", "or", "and",
    )

    @Volatile private var target: File? = null

    @Volatile private var snapshot: Pair<Long, Map<String, JSONObject>>? = null

    fun init(context: Context) = initFile(File(File(context.filesDir, DIR), FILE))

    /** JVM 测试入口：不依赖 Android 类型。 */
    @JvmStatic
    fun initFile(file: File) {
        file.parentFile?.mkdirs()
        target = file
        snapshot = null
    }

    /** JVM 测试入口：清状态。 */
    @JvmStatic
    fun reset() {
        target = null
        snapshot = null
    }

    // ── 纯函数 ──────────────────────────────────────────────────────────────────

    /**
     * 从一段错误文案里抽出档位集合：网关把合法枚举说全了的形状。
     *
     * 三道前置条件，任一不满足就返回 null（宁可没学到，不可学错）：
     *  1. 文案里提到 `effort` 或 `reasoning`——把 "must be one of" 用在别的字段上
     *     （比如 `max_tokens must be one of ...`）时不会误学；
     *  2. 存在一个 `one of` 锚点，且**紧贴其后的列表**里出现白名单档位词；
     *  3. 列表在第一个既不是档位词也不是连接词（[LIST_FILLERS]）的词处结束。
     *
     * 「贴着锚点的列表」而不是「同一段文案里恰好也有档位词」是这里的关键：`low` /
     * `high` / `none` / `max` 都是普通英文单词，散文里同样会出现——
     * `...upgrade is one of the recommended steps; a high traffic month...`
     * 这样的句子按旧口径会学出一个 `[high, medium]`，然后把该 host+model 钉死 30 天
     * （少档位、静默降级）。误学的代价是用户可见的，漏学一次没有任何代价，所以按
     * 要求列表形状从严。同理，文案里可能有多个 `one of`（前置无关子句），逐个锚点
     * 试过去、取第一个成形的列表，而不是只看第一个锚点。
     *
     * 去重、保序。
     */
    fun enumFromText(text: String): List<String>? {
        val lower = text.lowercase()
        if (lower.indexOf("effort") < 0 && lower.indexOf("reasoning") < 0) return null
        var from = 0
        while (true) {
            val idx = lower.indexOf(ONE_OF, from)
            if (idx < 0) return null
            val tiers = tiersAfterAnchor(lower, idx + ONE_OF.length)
            if (tiers.isNotEmpty()) return tiers
            from = idx + ONE_OF.length
        }
    }

    /**
     * 读 [start] 之后那个贴着锚点的档位列表：跳过非字母字符与 [LIST_FILLERS]，
     * 收档位词，遇到第一个别的词就停。扫描上限 [SCAN_WINDOW]，所以远处同款词
     * 不会被算进来。
     */
    private fun tiersAfterAnchor(lower: String, start: Int): List<String> {
        val end = minOf(lower.length, start + SCAN_WINDOW)
        val out = ArrayList<String>()
        var i = start
        while (i < end) {
            // A JSON-escaped control character ("\n low") would otherwise present its
            // escape letter ("n") as a word that ends the list before it starts — the
            // SSE hook passes `event.toString()`, which escapes newlines that way.
            if (lower[i] == '\\') {
                i += 2
                continue
            }
            if (lower[i] !in 'a'..'z') {
                i++
                continue
            }
            var j = i
            while (j < end && lower[j] in 'a'..'z') j++
            val word = lower.substring(i, j)
            when {
                word in KNOWN_TIERS -> if (word !in out) out.add(word)
                word in LIST_FILLERS -> Unit
                else -> return out
            }
            i = j
        }
        return out
    }

    /**
     * [fix/thinking-level-ceiling-1118] 第二种一手真相的形状：网关只点名被拒的那一档，
     * 不枚举合法集合。
     *
     * ```
     * {"error":{"message":"Thinking level is unsupported: THINKING_LEVEL_XHIGH"}}
     * ```
     *
     * 这是把 `reasoning_effort` 翻成 Gemini `thinking_level` 的中转回来的话（实测
     * `127.0.0.1:8790` → `antigravity/gemini-4-hydrogen-sonar`，2026-11-18）。它和
     * [enumFromText] 的信息量不同：那边是完整枚举，这边只知道**这一档不被接受**。
     * 于是学到的是一条**上限**——"严格弱于被拒档位的那一串"（[ThinkingEffortLadder.tiersBelow]），
     * 落盘结构与枚举完全相同，由 [ThinkingEffortLadder.clampEffort] 在最靠近请求的
     * 位置落档。
     *
     * 为什么这条值得单独解析：没有它时，[record] 从这次 400 里什么都学不到，下一次
     * 请求（包括用户点重试）依然原样发出 `xhigh`，撞同一堵墙；而**重试路径复用的是
     * 原模型的配置**，所以"自动切到别的模型"并不能救它。
     *
     * 被拒一次学一档：若下一档也被拒，会再学一次。每次学习都发生在真失败之后，
     * 不预探测、不额外消耗 token。
     *
     * 三道前置，任一不满足就返回 null（宁可没学到，不可学错）：
     *  1. 文案里有「不支持」类措辞（[UNSUPPORTED_MARKERS]）——否则 `Thinking is a
     *     strong selling point` 这类正常文案也会被当成拒绝；
     *  2. 文案谈的是 thinking / effort / reasoning 之一——把「temperature 太高不支持」
     *     这类别的字段排除在外；
     *  3. 有一个 `level` 锚点，其后（允许夹 [LEVEL_FILLERS]）紧跟白名单档位词：
     *     `THINKING_LEVEL_XHIGH` 的 `level_xhigh`、`"thinkingLevel": "xhigh"`、
     *     `thinking level: xhigh` 都命中。锚点后跟的不是档位词
     *     （`thinking level is not supported for this model` 里的 `is`）时继续找下一个
     *     锚点，全找不到就学不到——那句文案本来就没说答案。
     */
    fun ceilingFromText(text: String): List<String>? {
        val lower = text.lowercase()
        if (UNSUPPORTED_MARKERS.none { lower.contains(it) }) return null
        val aboutEffort = lower.indexOf("thinking") >= 0 || lower.indexOf("effort") >= 0 ||
            lower.indexOf("reasoning") >= 0
        if (!aboutEffort) return null
        val rejected = rejectedTierAfterLevel(lower) ?: return null
        return ThinkingEffortLadder.tiersBelow(rejected).takeIf { it.isNotEmpty() }
    }

    /** 逐个 `level` 锚点找第一个紧跟白名单档位词的；找不到返回 null。 */
    private fun rejectedTierAfterLevel(lower: String): String? {
        var from = 0
        while (true) {
            val idx = lower.indexOf(LEVEL, from)
            if (idx < 0) return null
            val start = idx + LEVEL.length
            val end = minOf(lower.length, start + LEVEL_WINDOW)
            val word = firstWordAfter(lower, start, end)
            if (word != null && word in KNOWN_TIERS) return word
            from = start
        }
    }

    /** 跳过非字母与 [LEVEL_FILLERS]，取 [start] 之后第一个实词（上限 [end]）。 */
    private fun firstWordAfter(lower: String, start: Int, end: Int): String? {
        var i = start
        while (i < end) {
            if (lower[i] !in 'a'..'z') {
                i++
                continue
            }
            var j = i
            while (j < end && lower[j] in 'a'..'z') j++
            val word = lower.substring(i, j)
            if (word !in LEVEL_FILLERS) return word
            i = j
        }
        return null
    }

    /** 从一次请求体里取 model 字段（学的是 host+model 维度的事实）。 */
    fun modelIdFrom(requestBody: String): String? = try {
        JSONObject(requestBody).optString(KEY_MODEL).takeIf { it.isNotEmpty() && it.length <= 200 }
    } catch (_: JSONException) {
        null
    }

    // ── 存储 ────────────────────────────────────────────────────────────────────

    /**
     * 从一次失败请求里学。**返回学到了什么**（null = 这次没有可学的信息）；
     * 学到时的日志在 [write] 内打出，调用方不必（也不）用返回值判断。
     */
    fun record(host: String, requestBody: String, errorBody: String): List<String>? {
        // 两种形状：说全了枚举的（[enumFromText]），和只点名被拒档位的
        // （[ceilingFromText]，Gemini 系 `THINKING_LEVEL_*`）。前者信息更全，先问它。
        val tiers = enumFromText(errorBody) ?: ceilingFromText(errorBody) ?: return null
        val model = modelIdFrom(requestBody) ?: return null
        if (host.isBlank() || model.isBlank()) return null
        write(key(host, model), tiers)
        return tiers
    }

    /** 当前有效的 learned 集合；没学到、已过期或尚未 init → null。 */
    fun learned(host: String, modelId: String): List<String>? {
        if (host.isBlank() || modelId.isBlank()) return null
        val now = System.currentTimeMillis()
        val file = target ?: return null
        val stamp = stampOf(file)
        val snap = snapshot
        if (snap != null && snap.first == stamp) {
            return tiersOf(snap.second[key(host, modelId)], now)
        }
        val loaded = load(file) ?: return null
        snapshot = stamp to loaded
        return tiersOf(loaded[key(host, modelId)], now)
    }

    /** 已学习条目的 key 集合（诊断用；含过期的）。 */
    @Synchronized
    fun learnedKeys(): List<String> {
        val t = target ?: return emptyList()
        return load(t)?.keys?.sorted() ?: emptyList()
    }

    private fun key(host: String, model: String) = host.trimEnd('/') + "/" + model

    private fun tiersOf(entry: JSONObject?, now: Long): List<String>? {
        if (entry == null) return null
        val at = entry.optLong("at")
        if (at <= 0 || now - at > TTL_MS) return null
        val arr = entry.optJSONArray("tiers") ?: return null
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).lowercase()
            if (v in KNOWN_TIERS && v !in out) out.add(v)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun load(file: File): Map<String, JSONObject>? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val root = JSONObject(file.readText())
            val entries = root.optJSONObject("entries") ?: return null
            val out = HashMap<String, JSONObject>()
            val names = entries.names() ?: return out
            for (i in 0 until names.length()) {
                val k = names.optString(i) ?: continue
                entries.optJSONObject(k)?.let { out[k] = it }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 原子写：临时文件 + rename。两个进程都可能走到这里（worker 发请求时学、
     * host 里的手工重试也会），rename 保证读到的永远是完整文件。
     */
    @Synchronized
    private fun write(k: String, tiers: List<String>) {
        val f = target ?: return
        val now = System.currentTimeMillis()
        val existing = load(f) ?: HashMap()
        val entry = JSONObject()
            .put("tiers", JSONArray(tiers))
            .put("at", now)
        val entries = JSONObject()
        for ((oldKey, oldEntry) in existing) {
            val oldAt = oldEntry.optLong("at")
            if (oldAt <= 0 || now - oldAt > TTL_MS * 3) continue   // 顺手清陈旧的
            entries.put(oldKey, oldEntry)
        }
        entries.put(k, entry)
        val body = JSONObject().put("entries", entries).toString()
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (tmp.renameTo(f)) {
                snapshot = null   // 立刻失效，下次读取重新加载
            } else {
                f.writeText(body)
                tmp.delete()
                snapshot = null
            }
            AppLogger.info(TAG, "learned effort tiers for $k from the gateway: $tiers")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cannot persist learned effort tiers: ${t.message}")
        }
    }
}
