package com.aifriend.task.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 任务候选选择请求。
 *
 * @param candidateId 当前会话候选编号
 * @param expectedVersion 客户端看到的会话版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskSelectionReq(
        @NotBlank @Size(max = 64) String candidateId,
        @Min(1) long expectedVersion) {
}
