package com.aifriend.retrieval.application;

import java.util.List;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;

/**
 * 在一次新的权威一致性读取中复验完整来源及适用范围；不得使用旧请求快照或缓存。
 * 该能力只合并单个复验阶段的重复读取，不合并提交前、返回前等不同阶段。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeEvidencePort extends KnowledgeRepositoryPort {
    /**
     * 读取当前完整索引并核对版本、原文归属、删除状态、语言及应用版本。
     * @param version 请求证据的索引世代
     * @param evidence 最多四段完整证据
     * @param locale 请求语言
     * @param appVersion 请求应用版本
     * @return 全部证据及适用范围仍有效；存储损坏或故障不能返回true
     */
    boolean isCurrentForScope(IndexVersion version, List<KnowledgeChunk> evidence, String locale, int appVersion);
}
