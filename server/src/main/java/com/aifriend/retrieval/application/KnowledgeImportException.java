package com.aifriend.retrieval.application;

/**
 * 导入业务故障，只携带固定枚举；数据库提交结果未知不得映射为这些确定性结果。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeImportException extends RuntimeException {
    /** 有限导入业务原因。 */
    public enum Kind {
        /** 同一幂等键正文/profile变化。 */ IDEMPOTENCY_CONFLICT,
        /** 来源已删除，不允许旧请求恢复。 */ SOURCE_DELETED,
        /** 文档或待处理作业达到上限。 */ RESOURCE_LIMIT,
        /** 管理目标不存在。 */ NOT_FOUND,
        /** 文档修订号已变化。 */ VERSION_MISMATCH
    }
    /** 可序列化的固定失败类别，不含敏感原文。 */
    private final Kind kind;

    /**
     * 创建不包含用户输入的错误。
     * @param kind 固定原因
     */
    public KnowledgeImportException(Kind kind) {
        super(java.util.Objects.requireNonNull(kind, "kind").name());
        this.kind = kind;
    }
    /**
     * 读取确定性的导入业务失败原因。
     * @return 固定原因
     */
    public Kind kind() { return kind; }
}
