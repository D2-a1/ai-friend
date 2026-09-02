package com.aifriend.reminder.application;

import java.time.Instant;

/**
 * 个人提醒调度端口。
 *
 * <p>当前仅占位，不注册实现、不开放 API，也不能自动触发微信外发。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ReminderSchedulerPort {

    /**
     * 安排本地或服务端提醒。
     *
     * @param reminderId 提醒标识
     * @param triggerAt UTC 触发时间
     */
    void schedule(String reminderId, Instant triggerAt);
}
