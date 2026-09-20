package com.aifriend.retrieval.application;

import java.util.List;
import java.util.Optional;

import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;

/**
 * 公开知识的权威读取及返回前复验边界，不负责收费调用。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeRepositoryPort {
    /**
     * 读取一致、完整、有界的当前索引语料；空表示尚无ACTIVE，不把存储异常当空。
     * @return 已验证快照
     */
    Optional<Snapshot> readActive();

    /**
     * 在新的短事务检查控制版本、文档删除状态及活动版本。
     * @param version 请求使用的世代
     * @param evidence 即将返回的来源
     * @return 所有证据是否仍有效；异常不得返回true
     */
    boolean isCurrent(IndexVersion version, List<KnowledgeChunk> evidence);

    /**
     * 完整语料快照；构造时拒绝缺失来源与部分加载。
     * @param version 索引版本
     * @param documents 活动文档版本
     * @param chunks 完整片段清单
     */
    record Snapshot(IndexVersion version, List<KnowledgeDocument> documents, List<KnowledgeChunk> chunks) {
        /** 防御性复制并校验来源引用，不存向量副本或私人信息。 */
        public Snapshot {
            java.util.Objects.requireNonNull(version, "version");
            if (documents == null || chunks == null || documents.size() > 100 || chunks.size() > 2000) {
                throw new IllegalArgumentException("INVALID_CORPUS");
            }
            documents = List.copyOf(documents);
            chunks = List.copyOf(chunks);
            var sources = new java.util.HashMap<java.util.UUID, KnowledgeDocument>();
            for (var document : documents) {
                if (sources.put(document.id(), document) != null) {
                    throw new IllegalArgumentException("DUPLICATE_DOCUMENT");
                }
            }
            var ids = new java.util.HashSet<java.util.UUID>();
            var ordinals = new java.util.HashSet<String>();
            for (var chunk : chunks) {
                var document = sources.get(chunk.documentId());
                if (document == null || document.version() != chunk.documentVersion()
                        || !ids.add(chunk.id()) || !ordinals.add(chunk.documentId() + ":" + chunk.ordinal())
                        || !chunk.chunkerVersion().equals(version.chunkerVersion())
                        || !com.aifriend.retrieval.domain.KnowledgeText.slice(
                                document.text(), chunk.sourceStart(), chunk.sourceEnd()).equals(chunk.text())) {
                    throw new IllegalArgumentException("INVALID_CHUNK_SOURCE");
                }
            }
            for (var document : documents) {
                var parts = chunks.stream().filter(chunk -> chunk.documentId().equals(document.id()))
                        .sorted(java.util.Comparator.comparingInt(KnowledgeChunk::ordinal)).toList();
                int covered = 0;
                for (int i = 0; i < parts.size(); i++) {
                    var part = parts.get(i);
                    if (part.ordinal() != i || (i == 0 && part.sourceStart() != 0)
                            || part.sourceStart() > covered || part.sourceEnd() <= covered) {
                        throw new IllegalArgumentException("INCOMPLETE_CORPUS");
                    }
                    covered = part.sourceEnd();
                }
                if (covered != document.text().codePointCount(0, document.text().length())) {
                    throw new IllegalArgumentException("INCOMPLETE_CORPUS");
                }
            }
        }
    }
}
