package com.aifriend.retrieval.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 原文连续片段；偏移按 Unicode code point 左闭右开。
 * @param id 稳定片段标识
 * @param documentId 来源文档
 * @param documentVersion 来源版本
 * @param ordinal 版本内序号
 * @param heading 最近标题
 * @param text 原文内容
 * @param sourceStart 原文起点
 * @param sourceEnd 原文终点
 * @param chunkerVersion 分块算法版本
 * @author codex
 * @since 1.0.0
 */
public record KnowledgeChunk(UUID id, UUID documentId, long documentVersion, int ordinal,
        String heading, String text, int sourceStart, int sourceEnd, String chunkerVersion) {
    /** 校验硬上限及来源区间的一致性。 */
    public KnowledgeChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "documentId");
        text = KnowledgeText.require(text, 600, 2400, true);
        heading = KnowledgeText.require(heading, 200, 800, true);
        if (documentVersion < 1 || ordinal < 0 || ordinal >= 2000 || sourceStart < 0
                || sourceEnd <= sourceStart || sourceEnd > 262144
                || sourceEnd - sourceStart != text.codePointCount(0, text.length())
                || chunkerVersion == null || !chunkerVersion.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("INVALID_CHUNK");
        }
    }

    /** 内容不能通过默认日志字符串泄露。 */
    @Override public String toString() { return "KnowledgeChunk[content=redacted]"; }
}
