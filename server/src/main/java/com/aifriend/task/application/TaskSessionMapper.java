package com.aifriend.task.application;

import org.springframework.stereotype.Component;

import com.aifriend.shared.security.PublicIdCodec;

/**
 * 持久化任务会话到最小 API 视图的映射器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class TaskSessionMapper {

    private final TaskPayloadCodec payloadCodec;

    /**
     * 创建任务会话映射器。
     *
     * @param payloadCodec 敏感载荷保护器
     */
    public TaskSessionMapper(TaskPayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec;
    }

    /**
     * 解密当前 owner 已授权读取的任务载荷并构造响应。
     *
     * @param session owner 范围会话快照
     * @return 最小任务会话视图
     */
    public TaskSessionView toView(TaskStoredSession session) {
        TaskPayload payload = payloadCodec.decode(session.payloadCipher());
        return new TaskSessionView(
                PublicIdCodec.taskSessionId(session.id()), session.sessionVersion(),
                session.state(), payload.understanding(), payload.candidates(),
                payload.spokenSummary(), session.summaryHash(),
                payload.allowedActions(), payload.channelResult(), session.expiresAt());
    }
}
