package com.aifriend.core.reminder

import java.time.Instant

/**
 * 本地提醒调度端口。
 *
 * 当前只占位，不注册实现、不显示 UI，也不能自动触发微信外发。
 */
interface ReminderSchedulerPort {
    /** 安排一个使用 UTC 时间的提醒。 */
    suspend fun schedule(reminderId: String, triggerAt: Instant)
}
