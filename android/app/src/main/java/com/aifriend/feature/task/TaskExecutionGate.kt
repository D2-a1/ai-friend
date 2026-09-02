package com.aifriend.feature.task

import javax.inject.Inject

/**
 * 当前进程任务的执行代次门闩。
 *
 * 取消时先同步关闭当前代次，然后再停止异步录音或网络工作。迟到响应
 * 只能读取失效代次，不能重新打开执行权。该门闩不持久化，不代表服务端任务状态。
 */
class TaskExecutionGate @Inject constructor() {
    private var generation = 0L
    private var blocked = true

    /** 开启一个新任务代次，同时使旧代次全部失效。 */
    @Synchronized
    fun open(): Token {
        generation++
        blocked = false
        return Token(generation)
    }

    /** 立即关闭指定当前代次；迟到的旧代次无权影响新任务。 */
    @Synchronized
    fun block(token: Token): Boolean {
        if (token.value != generation) return false
        blocked = true
        return true
    }

    /** 使当前代次失效，用于页面退出或会话彻底清理。 */
    @Synchronized
    fun invalidate() {
        generation++
        blocked = true
    }

    /** 判断代次是否仍是当前任务，不代表它仍允许执行。 */
    @Synchronized
    fun isCurrent(token: Token): Boolean = token.value == generation

    /** 只有未取消的当前代次才能继续业务动作。 */
    @Synchronized
    fun canContinue(token: Token): Boolean = token.value == generation && !blocked

    /** 不含任务正文或服务端标识的本地代次令牌。 */
    @JvmInline
    value class Token internal constructor(internal val value: Long)
}
