package com.aifriend.knowledge.application;

import java.util.Objects;

/**
 * 图谱投影固定错误，禁止携带数据库参数或私人引用。
 * @author Codex
 * @since 1.0.0
 */
public final class GraphProjectionException extends RuntimeException {
    /** 投影失败类型。 */
    public enum Kind {
        /** 发布版本冲突，不自动重放写入。 */ CONFLICT,
        /** 持久化投影不完整或损坏。 */ INVALID,
        /** 数据库或提交不可用，提交结果可能未知。 */ STORAGE_UNAVAILABLE
    }
    /** 可序列化的固定失败类别，不含敏感原文。 */
    private final Kind kind;

    /**
     * 创建无敏感原因链的错误。
     * @param kind 固定类别
     */
    public GraphProjectionException(Kind kind) {
        super(Objects.requireNonNull(kind).name());
        this.kind = kind;
    }

    /**
     * 读取投影发布或恢复的失败类别。
     * @return 固定类别
     */
    public Kind kind() { return kind; }
}
