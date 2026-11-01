package com.rikkaminis.app.tools

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.rikkaminis.app.agent.APP_KNOWLEDGE_ASSETS_DIR
import com.rikkaminis.app.agent.AppKnowledgeIndex
import com.rikkaminis.app.agent.AppKnowledgePack
import com.rikkaminis.app.agent.parseAppKnowledgePack
import com.rikkaminis.app.agent.renderAppKnowledge
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [knowledge-pack] `app_guide` — read-only lookup of the built-in app UI
 * guides shipped under `assets/app_knowledge/<id>/APP.md`.
 *
 * Division of labour: parsing / indexing / rendering are Android-free and
 * live in [com.rikkaminis.app.agent.AppKnowledge]; this file only does the
 * AssetManager IO, the lazy cache and the tool contract.
 *
 * Two deliberate limits:
 *  - The pack body is NOT injected into the system prompt — it is fetched on
 *    demand. Putting 4 packs of Chinese prose in front of every request would
 *    break the prompt prefix cache for a fact any single turn may not need.
 *  - The index is built once per process and never invalidated: assets are
 *    read-only at runtime, so a rebuild could only produce the same answer.
 */
object AppGuideTool {

    const val NAME = "app_guide"
    private const val TAG = "AppGuideTool"

    @Volatile
    private var cachedIndex: AppKnowledgeIndex? = null

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read the built-in UI guide for a well-known Android app before driving " +
            "its screen. The guide covers the app's page structure, common entry points, " +
            "easy-to-confuse spots and login/security pages, so you can navigate from " +
            "knowledge instead of exploring from scratch. Read-only, no side effects. " +
            "The query accepts a package name (e.g. com.tencent.mm) or an app name/alias " +
            "(e.g. WeChat, wechat, weixin). When nothing matches, the result lists the guides " +
            "that exist.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Look up WeChat settings path', 'Read Taobao order page guide'). Use the same language as the user."),
            "query" to AgentToolParam("string", "Package name (com.tencent.mm) or app name/alias (WeChat, wechat, weixin, system settings)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val toolTitle = resolveToolTitle(argsJson, NAME)
        val query = runCatching { JSONObject(argsJson).optString("query", "") }.getOrDefault("")
        if (query.isBlank()) {
            return ToolExecutionResult(
                "Error: 'query' is required — pass a package name (com.tencent.mm) or an app name (WeChat).",
                false,
                toolTitle = toolTitle,
            )
        }

        val index = indexFor(context)
        val entry = index.find(query)
            ?: return ToolExecutionResult(missMessage(query, index), false, toolTitle = toolTitle)

        val body = readPackBody(context.assets, entry.id)
            ?: return ToolExecutionResult(
                "Error: guide '${entry.id}' matched but its body could not be read " +
                    "($APP_KNOWLEDGE_ASSETS_DIR/${entry.id}/APP.md).",
                false,
                toolTitle = toolTitle,
            )
        return ToolExecutionResult(renderAppKnowledge(entry, body), true, toolTitle = toolTitle)
    }

    /** Index over every parseable pack; an empty asset dir yields an empty index. */
    internal fun indexFor(context: Context): AppKnowledgeIndex =
        cachedIndex ?: buildIndex(context.assets).also { cachedIndex = it }

    /**
     * Read + parse every `<dir>/<id>/APP.md`. Best-effort by construction: a
     * missing asset directory, an unreadable file or a malformed pack is
     * skipped with a log line — this runs on the tool path, and a knowledge
     * asset must never be able to break a turn.
     */
    internal fun buildIndex(assets: AssetManager): AppKnowledgeIndex {
        val packs = mutableListOf<AppKnowledgePack>()
        val ids = runCatching { assets.list(APP_KNOWLEDGE_ASSETS_DIR)?.toList().orEmpty() }
            .getOrElse {
                Log.w(TAG, "cannot list $APP_KNOWLEDGE_ASSETS_DIR: ${it.message}")
                emptyList()
            }
        for (id in ids.sorted()) {
            val text = readPackBody(assets, id) ?: continue
            val pack = parseAppKnowledgePack(id, text)
            if (pack == null) {
                Log.w(TAG, "skipping unparseable pack: $id")
                continue
            }
            packs.add(pack)
        }
        if (packs.isEmpty()) Log.w(TAG, "no app knowledge packs under $APP_KNOWLEDGE_ASSETS_DIR")
        return AppKnowledgeIndex(packs)
    }

    /** Raw asset text for one pack, or null when it is missing/unreadable. */
    internal fun readPackBody(assets: AssetManager, id: String): String? = runCatching {
        assets.open("$APP_KNOWLEDGE_ASSETS_DIR/$id/APP.md")
            .bufferedReader()
            .use { it.readText() }
    }.getOrElse {
        Log.w(TAG, "cannot read pack '$id': ${it.message}")
        null
    }

    /**
     * Miss reply. Names the guides that exist (capped) instead of a bare
     * rejection — the same rule [UnknownToolMessage] follows for tool names.
     */
    internal fun missMessage(query: String, index: AppKnowledgeIndex): String {
        val available = index.availableList()
        val tail = if (available.isEmpty()) {
            "No built-in app guides are installed on this device."
        } else {
            "Built-in guides: $available."
        }
        return "No built-in app knowledge pack matched \"$query\". $tail " +
            "Query by package name (com.tencent.mm) or by app name/alias (WeChat, 微信)."
    }
}
