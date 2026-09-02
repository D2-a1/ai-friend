package com.aifriend.task.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 受控微信渠道结果请求。
 *
 * @param planId 服务端动作计划编号
 * @param summaryHash 完整复述摘要哈希
 * @param result 受控汇总结果
 * @param parts 最多三个部分结果
 * @param ruleVersion 实际使用规则版本
 * @param occurredAt 客户端观察时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskChannelResultReq(
        @NotBlank @Size(max = 64) String planId,
        @NotBlank @Size(max = 100) String summaryHash,
        @NotBlank @Size(max = 30) String result,
        @Valid @Size(max = 3) List<TaskChannelPartReq> parts,
        @NotBlank @Size(max = 40) String ruleVersion,
        @NotNull Instant occurredAt) {

    /** 将可空 parts 规范为空列表。 */
    public TaskChannelResultReq {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
