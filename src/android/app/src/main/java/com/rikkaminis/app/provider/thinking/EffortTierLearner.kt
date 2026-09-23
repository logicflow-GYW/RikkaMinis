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

    /** 进程内缓存寿命：避免每次请求都读文件，同时让另一进程的写入较快生效。 */
    private const val CACHE_TTL_MS = 10_000L

    /** 从 "one of" 往后扫多少个字符找档位词，足够覆盖一条错误文案。 */
    private const val SCAN_WINDOW = 400

    private const val ONE_OF = "one of"
    private const val KEY_MODEL = "model"

    /**
     * 已知档位词。白名单而非全收：`one of` 之后跟的词不保证都是档位名
     * （"the following: low, medium" 里还有 "the"、"following"）。
     */
    val KNOWN_TIERS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "auto")

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
     * 从一段错误文案里抽出档位集合。
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
        val tiers = enumFromText(errorBody) ?: return null
        val model = modelIdFrom(requestBody) ?: return null
        if (host.isBlank() || model.isBlank()) return null
        write(key(host, model), tiers)
        return tiers
    }

    /** 当前有效的 learned 集合；没学到、已过期或尚未 init → null。 */
    fun learned(host: String, modelId: String): List<String>? {
        if (host.isBlank() || modelId.isBlank()) return null
        val now = System.currentTimeMillis()
        val snap = snapshot
        if (snap != null && now - snap.first < CACHE_TTL_MS) {
            return tiersOf(snap.second[key(host, modelId)], now)
        }
        val loaded = load(target ?: return null) ?: return null
        snapshot = now to loaded
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
