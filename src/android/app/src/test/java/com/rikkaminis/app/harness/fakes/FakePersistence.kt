package com.rikkaminis.app.harness.fakes

import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.PersistenceScript

/**
 * 可脚本化的 FakePersistence。
 *
 * 记录持久化标记和失败计数。
 * 支持 failOnWrite（中间写入失败）和 failOnFinalize（终态写入失败）。
 */
class FakePersistence(val script: PersistenceScript) {
    var finalMark: PersistenceMark? = null
        private set
    var writeFailed: Boolean = false
        private set
    var historyOverwritten: Boolean = false
        private set

    /** 写入一条持久化记录。返回是否成功。 */
    fun write(mark: PersistenceMark): Boolean {
        if (script.failOnWrite) {
            writeFailed = true
            return false
        }
        finalMark = mark
        return true
    }

    /** 终态持久化写入（finalize）。返回是否成功。 */
    fun finalize(mark: PersistenceMark): Boolean {
        if (script.failOnFinalize) {
            writeFailed = true
            return false
        }
        finalMark = mark
        return true
    }

    /** 标记历史被覆盖（用于 F09 验证）。 */
    fun markHistoryOverwritten() {
        historyOverwritten = true
    }

    fun reset() {
        finalMark = null
        writeFailed = false
        historyOverwritten = false
    }
}