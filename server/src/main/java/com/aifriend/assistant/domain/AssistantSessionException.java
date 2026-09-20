package com.aifriend.assistant.domain;

import java.util.Objects;

/**
 * 会话状态失败，仅携带固定原因，不带问句、账号或存储异常。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantSessionException extends RuntimeException {
    /** 可序列化的固定生命周期原因，不含用户输入。 */
    private final AssistantReason reason;

    /**
     * 创建固定原因异常。
     * @param reason 生命周期失败原因
     */
    public AssistantSessionException(AssistantReason reason) {
        super(Objects.requireNonNull(reason).name()); this.reason = reason;
    }

    /**
     * 读取会话生命周期的失败原因。
     * @return 固定原因
     */
    public AssistantReason reason() { return reason; }
}
