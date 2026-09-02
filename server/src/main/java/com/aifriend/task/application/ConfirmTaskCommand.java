package com.aifriend.task.application;

import java.time.Instant;

import com.aifriend.task.domain.TaskAction;

/**
 * 动作型安全指令确认命令。
 *
 * @param action CONFIRM_SEND、CONFIRM_CALL、REJECT 或 CANCEL
 * @param expectedVersion 客户端看到的会话版本
 * @param summaryHash 完整复述摘要哈希
 * @param recognizedTemplateId 本地命中的 vt_ 模板编号
 * @param recognizedAt 本地识别发生时间
 * @author Codex
 * @since 1.0.0
 */
public record ConfirmTaskCommand(
        TaskAction action,
        long expectedVersion,
        String summaryHash,
        String recognizedTemplateId,
        Instant recognizedAt) {
}
