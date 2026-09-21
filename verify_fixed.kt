// 修复后复核：渲染端不再加 "\n" 前缀，视觉行数应与原文完全一致。
// 用真实 chunkText（修复后版本）+ 修复后的渲染逻辑。
import com.rikkaminis.app.ui.components.chunkText
import java.io.File

/** 视觉行数 = 每个 item 文本里 '\n' 的个数 + 1（跨 item 由布局分隔，不计入 item 内） */
fun visualLines(text: String, prefixMode: String): Int {
    val chunks = chunkText(text)
    if (chunks.isEmpty()) return 0
    return chunks.mapIndexed { i, c ->
        when {
            prefixMode == "fixed" -> c                                  // 修复后
            prefixMode == "buggy" && i > 0 -> "\n$c"                    // 修复前
            else -> c
        }
    }.sumOf { item -> item.count { it == '\n' } + 1 }
}

fun sourceLines(text: String): Int = text.split('\n').size

fun report(name: String, text: String) {
    val chunks = chunkText(text)
    val src = sourceLines(text)
    val buggy = visualLines(text, "buggy")
    val fixed = visualLines(text, "fixed")
    println("── $name")
    println("   chars=${text.length}  chunks=${chunks.size}  原文行数=$src")
    println("   修复前(+前缀) 视觉行数=$buggy  膨胀=${buggy - src}")
    println("   修复后(无前缀) 视觉行数=$fixed  膨胀=${fixed - src}  ${if (fixed == src) "OK" else "MISMATCH"}")
    // 跨 chunk 选区复制：SelectionManager 自己会在 selectable 之间补 '\n'
    val copyBuggy = chunks.mapIndexed { i, c -> if (i > 0) "\n$c" else c }.joinToString("\n")
    val copyFixed = chunks.joinToString("\n")
    println("   跨 chunk 复制文本行数: 修复前=${copyBuggy.split('\n').size}  修复后=${copyFixed.split('\n').size}  (原文=$src)")
    println("   复制文本 == 原文 ? 修复前=${copyBuggy == text}  修复后=${copyFixed == text}")
}

fun main() {
    println("=== 合成用例 ===")
    report("100 行", (1..100).joinToString("\n") { "line $it with some content" })
    report("含空行 + 尾换行", "a\n\nb\n\n\nc\n")
    report("单行超长", "x".repeat(50_000))

    println()
    println("=== 真实记忆文件 ===")
    for (p in listOf("/var/minis/memory/GLOBAL.md", "/var/minis/memory/2026-09-20.md")) {
        val f = File(p)
        if (!f.exists()) { println("── $p (缺失)"); continue }
        report(f.name, f.readText())
    }
}
