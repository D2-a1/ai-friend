package com.aifriend.task.api;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aifriend.task.domain.TaskAction;

/**
 * 用户听完系统复述后的语音确认请求。
 *
 * @param action 确认发送、确认通话、拒绝或取消
 * @param expectedVersion 客户端看到的会话版本
 * @param summaryHash 系统复述摘要哈希
 * @param confirmedAt 用户说出确认或否认的时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskConfirmationReq(
        @NotNull TaskAction action,
        @Min(1) long expectedVersion,
        @NotBlank @Size(max = 100) String summaryHash,
        @NotNull Instant confirmedAt) {
}