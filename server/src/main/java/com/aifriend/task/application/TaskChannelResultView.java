package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;

/**
 * 微信渠道执行结果最小归档。
 *
 * @param result 受控汇总结果
 * @param parts 最多三个部分结果
 * @param occurredAt 客户端观察时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskChannelResultView(
        String result,
        List<TaskChannelPartView> parts,
        Instant occurredAt) {

    /** 固化部分结果。 */
    public TaskChannelResultView {
        parts = List.copyOf(parts);
    }
}
