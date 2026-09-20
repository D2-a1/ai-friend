package com.aifriend.retrieval.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 单一公开文档版本；不是可自动发布的索引。
 * @param id 文档 ID
 * @param sourceKey 管理导入键，不是文件路径或 URL
 * @param version 文档版本
 * @param title 纯文本标题
 * @param locale 明确语言标签
 * @param minAppVersion 最低适用 versionCode
 * @param maxAppVersion 最高适用 versionCode
 * @param text 保留来源原文
 * @author codex
 * @since 1.0.0
 */
public record KnowledgeDocument(UUID id, String sourceKey, long version, String title,
        String locale, int minAppVersion, int maxAppVersion, String text) {
    /** 校验单文档硬限额，不接受路径或半组版本范围。 */
    public KnowledgeDocument {
        Objects.requireNonNull(id, "id");
        if (sourceKey == null || !sourceKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || version < 1 || minAppVersion < 1 || maxAppVersion < minAppVersion) {
            throw new IllegalArgumentException("INVALID_DOCUMENT");
        }
        title = KnowledgeText.require(title, 200, 800, false);
        if (locale == null || !locale.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")
                || locale.length() > 35) {
            throw new IllegalArgumentException("INVALID_LOCALE");
        }
        text = KnowledgeText.require(text, 262144, 262144, false);
    }

    /**
     * 仅精确语言及闭区间版本匹配，不默认用最新指南。
     * @param queryLocale 问题语言
     * @param appVersion 客户端 versionCode
     * @return 是否适用
     */
    public boolean appliesTo(String queryLocale, int appVersion) {
        return locale.equalsIgnoreCase(queryLocale)
                && appVersion >= minAppVersion && appVersion <= maxAppVersion;
    }

    /** 防止默认 record 字符串把文档内容写入日志。 */
    @Override public String toString() { return "KnowledgeDocument[content=redacted]"; }
}
