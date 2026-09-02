package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;

/**
 * 受控微信渠道结果上报命令。
 *
 * @param planId 服务端有限动作计划编号
 * @param summaryHash 完整复述摘要哈希
 * @param result 受控汇总结果
 * @param parts 最多三个部分结果
 * @param ruleVersion 实际使用的规则版本
 * @param occurredAt 客户端观察时间
 * @author Codex
 * @since 1.0.0
 */
public record ReportTaskChannelResultCommand(
        String planId,
        String summaryHash,
        String result,
        List<TaskChannelPartView> parts,
        String ruleVersion,
        Instant occurredAt) {

    /** 固化部分结果。 */
    public ReportTaskChannelResultCommand {
        parts = List.copyOf(parts);
    }
}
