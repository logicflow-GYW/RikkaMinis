package com.rikkaminis.app.sandbox

/**
 * native-offload abstract socket 取名（纯函数，JVM 可测）。
 *
 * 名字必须同时区分**安装身份**与**运行实例身份**：
 * - 安装身份 = applicationId：同一设备上并存的 lab / stable 构建
 *   （`com.rikkaminis.app.lab` vs `com.rikkaminis.app`）各绑自己的名字。
 * - 实例身份 = uid：Android 的应用双开 / 多用户运行的是**同一 applicationId
 *   的不同 uid 实例**（数据目录 `/data/user/<userId>/<pkg>`）。abstract socket
 *   名在同一 network namespace 内全局唯一，只按包名命名会让第二个实例
 *   `LocalServerSocket(name)` 全部 attempt 失败 → [NativeOffloadServer] 抛出
 *   IOException → `MinisApp.onCreate` 崩溃并进入永久重启循环（该文件 bind
 *   失败路径的既有后果）。加 uid 后每个实例各绑自己的名字。
 *
 * libproot 的 native_offload 扩展通过 `--native-offload=<name>:<handlers>`
 * 参数接收这个名字（见 PRootKernel / PersistentShell / TerminalSession），
 * 所以 Kotlin 侧与 C 侧始终一致，改名不需要改 native。
 */
internal const val OFFLOAD_SOCKET_BASE = "native-offload"

/** 组装 abstract socket 名：`native-offload-<appId 下划线化>-<uid>`。 */
internal fun offloadSocketName(applicationId: String, uid: Int): String =
    OFFLOAD_SOCKET_BASE + "-" + applicationId.replace('.', '_') + "-" + uid
