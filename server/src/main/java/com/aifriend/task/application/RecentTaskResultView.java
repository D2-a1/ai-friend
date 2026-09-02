package com.aifriend.task.application;

import java.time.Instant;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskState;

/**
 * 最近任务结果的最小公开视图。
 *
 * <p>该视图不得增加录音、识别文字、消息正文、联系人或动作计划字段。
 *
 * @param sessionId ts_ 前缀任务编号
 * @param state 任务终态
 * @param intent 任务动作类型
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @author Codex
 * @since 1.0.0
 */
public record RecentTaskResultView(
        String sessionId,
        TaskState state,
        TaskIntent intent,
        Instant createdAt,
        Instant updatedAt) {
}
