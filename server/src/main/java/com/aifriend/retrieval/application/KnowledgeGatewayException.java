package com.aifriend.retrieval.application;

/**
 * 知识网关固定故障，不保存上游正文、URL、密钥或原始异常。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeGatewayException extends RuntimeException {
    /** 故障类别，供问答层映射到业务原因。 */
    public enum Kind {
        /** 参数或供应商权限永久错误。 */ CONFIGURATION,
        /** 超时、429或5xx。 */ TEMPORARY,
        /** 响应协议损坏。 */ PROTOCOL,
        /** 端点、DNS或重定向被拒绝。 */ ENDPOINT_REJECTED,
        /** 并发额度耗尽。 */ BUSY
    }
    /** 可序列化的固定失败类别，不含敏感原文。 */
    private final Kind kind;

    /**
     * 创建脱敏错误。
     * @param kind 固定类别
     */
    public KnowledgeGatewayException(Kind kind) {
        super(java.util.Objects.requireNonNull(kind, "kind").name());
        this.kind = kind;
    }

    /**
     * 读取外部知识网关的脱敏故障类别。
     * @return 固定故障类别
     */
    public Kind kind() { return kind; }
}
