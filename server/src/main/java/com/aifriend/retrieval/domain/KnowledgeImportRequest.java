package com.aifriend.retrieval.domain;

import java.util.UUID;

/**
 * 管理员提交的公开文本导入请求；不是URL抓取或本地文件读取指令。
 * @param sourceKey 稳定公开来源键
 * @param title 标题
 * @param text 未规范化原文
 * @param locale 适用语言
 * @param minimumAppVersion 最低App版本
 * @param maximumAppVersion 最高App版本
 * @param idempotencyKey 高熵请求键，由客户端生成并保持重放不变
 * @author codex
 * @since 1.0.0
 */
public record KnowledgeImportRequest(String sourceKey, String title, String text, String locale,
        int minimumAppVersion, int maximumAppVersion, String idempotencyKey) {
    /** 复用来源硬限额校验，额外限制幂等键为无空白可打印ASCII。 */
    public KnowledgeImportRequest {
        new KnowledgeDocument(new UUID(0, 0), sourceKey, 1, title, locale, minimumAppVersion, maximumAppVersion, text);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new IllegalArgumentException("INVALID_IMPORT_KEY");
        }
    }

    /**
     * 存储锁内确定标识及版本后形成不可变原文。
     * @param id 文档标识
     * @param version 新文档版本
     * @return 有效公开文档
     */
    public KnowledgeDocument document(UUID id, long version) {
        return new KnowledgeDocument(id, sourceKey, version, title, locale, minimumAppVersion, maximumAppVersion, text);
    }

    @Override public String toString() { return "KnowledgeImportRequest[content=redacted]"; }
}
