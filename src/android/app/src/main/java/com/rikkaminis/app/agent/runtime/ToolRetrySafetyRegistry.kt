package com.rikkaminis.app.agent.runtime

/**
 * [T3-retry-side-effects] 受信任的工具副作用等级注册表。
 *
 * 副作用等级**只能**由这里（受信任 Kotlin 注册表）或系统内建 adapter
 * 指定。**不接受 LLM 在工具参数里自报安全等级后直接信任**——注册表
 * 查不到的工具一律按 [RetrySafety.UNKNOWN] 处理，调用方的安全声明
 * 只能降级（[RetrySafety.applyCallerClaim]），不能把 UNKNOWN 提升为
 * 更安全的等级。
 *
 * 默认分类（蓝图 T3 章节）：
 * - `file_read` / 目录列举 / 只读 browser 查询 → [RetrySafety.READ_ONLY]；
 * - 明确有确定目标、可重复设置并且有状态校验的操作 → [RetrySafety.IDEMPOTENT_WRITE]
 *   （当前没有任何工具默认归入此类：幂等写必须由调用点显式提供幂等键或
 *   执行后校验，注册表只登记"默认"等级，不替调用点承诺校验能力）；
 * - append / 发送 / 创建 / 删除 / 提交 / 发布 / 支付 → [RetrySafety.NON_IDEMPOTENT_WRITE]；
 * - 通用 `shell_execute` → [RetrySafety.UNKNOWN]。
 */
object ToolRetrySafetyRegistry {

    /**
     * 工具名 → 默认副作用等级。
     *
     * 注意：`browser_use` 整体为 UNKNOWN——它同时包含只读查询
     * （get_text/screenshot/scroll）与写操作（navigate/click/type/fetch）。
     * action 级细分（只读 browser 查询 = READ_ONLY）属于 T7 生产 adapter
     * 的职责，届时在调用点按 action 显式声明（只能降级）。
     */
    private val defaults: Map<String, RetrySafety> = mapOf(
        // 只读查询
        "file_read" to RetrySafety.READ_ONLY,
        "read_image" to RetrySafety.READ_ONLY,
        "memory_get" to RetrySafety.READ_ONLY,
        // 创建/覆盖/编辑文件：append 与覆盖语义下重复执行可能不一致
        "file_write" to RetrySafety.NON_IDEMPOTENT_WRITE,
        "file_edit" to RetrySafety.NON_IDEMPOTENT_WRITE,
        // 持久化写入、派生文件改写
        "memory_write" to RetrySafety.NON_IDEMPOTENT_WRITE,
        "memory_rollup" to RetrySafety.NON_IDEMPOTENT_WRITE,
        // 启动子 agent 有外部副作用
        "spawn_agent" to RetrySafety.NON_IDEMPOTENT_WRITE,
        // 混合读写 → 保守 UNKNOWN
        "browser_use" to RetrySafety.UNKNOWN,
        // 通用 shell 命令：无法从命令字符串证明安全性
        "shell_execute" to RetrySafety.UNKNOWN,
    )

    /** 返回受信任注册表中的默认等级；未注册工具一律 UNKNOWN。 */
    fun defaultSafetyFor(toolName: String): RetrySafety =
        defaults[toolName] ?: RetrySafety.UNKNOWN

    /** 该工具名是否在受信任注册表内（用于审计与测试断言）。 */
    fun isRegistered(toolName: String): Boolean = defaults.containsKey(toolName)

    /**
     * 受信任调用点查表（应用调用方声明的降级规则）。
     *
     * @param toolName 工具名；未注册返回 UNKNOWN。
     * @param callerClaim 调用点显式声明（可选）。只能降低自动化权限。
     */
    fun lookup(toolName: String, callerClaim: RetrySafety? = null): RetrySafety =
        defaultSafetyFor(toolName).applyCallerClaim(callerClaim)
}
