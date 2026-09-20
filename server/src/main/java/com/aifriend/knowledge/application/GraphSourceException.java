package com.aifriend.knowledge.application;

import java.util.Objects;

/**
 * 私人图谱来源的固定失败类别，不携带联系人、密文或底层异常信息。
 * @author Codex
 * @since 1.0.0
 */
public final class GraphSourceException extends RuntimeException {
    /** 来源失败类型。 */
    public enum Kind {
        /** 读取到损坏或混归属来源。 */ SOURCE_INVALID,
        /** 来源已变化，旧结果不能输出。 */ SOURCE_CHANGED,
        /** 完整图或显示结果超过有界上限。 */ GRAPH_LIMIT,
        /** 显示称呼无法安全解密。 */ DECRYPTION_FAILED,
        /** 数据库读取失败，不得解释为没有亲友。 */ STORAGE_UNAVAILABLE
    }

    /** 可序列化的固定失败类别，不含敏感原文。 */
    private final Kind kind;

    /**
     * 创建脱敏异常。
     * @param kind 固定类别
     */
    public GraphSourceException(Kind kind) {
        super(Objects.requireNonNull(kind, "kind").name());
        this.kind = kind;
    }

    /**
     * 读取图谱来源校验的失败类别。
     * @return 固定失败类别
     */
    public Kind kind() { return kind; }
}
