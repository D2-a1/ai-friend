package com.aifriend.task.application;

import java.time.Instant;

import com.aifriend.task.domain.TaskAction;

/**
 * 用户听完系统复述后的语音确认命令。
 *
 * @param action CONFIRM_SEND、CONFIRM_CALL、REJECT 或 CANCEL
 * @param expectedVersion 客户端看到的会话版本
 * @param summaryHash 系统复述摘要哈希
 * @param confirmedAt 用户说出确认或否认的时间
 * @author Codex
 * @since 1.0.0
 */
public record ConfirmTaskCommand(
        TaskAction action,
        long expectedVersion,
        String summaryHash,
        Instant confirmedAt) {
}