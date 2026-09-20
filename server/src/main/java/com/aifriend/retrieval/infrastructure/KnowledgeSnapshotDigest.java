package com.aifriend.retrieval.infrastructure;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Objects;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;

/**
 * 对完整公开语料清单作稳定、带长度前缀的SHA-256校验。
 * 不将全文拼接为第二份大缓冲，输入顺序不影响摘要。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeSnapshotDigest {
    /** 创建无状态公开语料摘要计算器。 */
    public KnowledgeSnapshotDigest() { }
    /**
     * 绑定世代、profile、算法、来源版本、元数据、片段及原文。
     * @param snapshot 已经过完整性校验的快照
     * @return 32字节摘要
     */
    public byte[] calculate(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                text(out, "knowledge-corpus-v1");
                var version = snapshot.version();
                text(out, version.generation().toString());
                out.writeLong(version.corpusRevision());
                text(out, version.tokenizerVersion());
                text(out, version.chunkerVersion());
                out.writeBoolean(version.embeddingProfile().isPresent());
                if (version.embeddingProfile().isPresent()) {
                    var profile = version.embeddingProfile().orElseThrow();
                    text(out, profile.id());
                    out.writeInt(profile.dimension());
                }
                var documents = snapshot.documents().stream()
                        .sorted(Comparator.comparing(document -> document.id().toString())).toList();
                out.writeInt(documents.size());
                for (var document : documents) {
                    text(out, document.id().toString());
                    text(out, document.sourceKey());
                    out.writeLong(document.version());
                    text(out, document.title());
                    text(out, document.locale());
                    out.writeInt(document.minAppVersion());
                    out.writeInt(document.maxAppVersion());
                    text(out, document.text());
                }
                var chunks = snapshot.chunks().stream()
                        .sorted(Comparator.comparing(chunk -> chunk.id().toString())).toList();
                out.writeInt(chunks.size());
                for (var chunk : chunks) {
                    text(out, chunk.id().toString());
                    text(out, chunk.documentId().toString());
                    out.writeLong(chunk.documentVersion());
                    out.writeInt(chunk.ordinal());
                    text(out, chunk.heading());
                    text(out, chunk.text());
                    out.writeInt(chunk.sourceStart());
                    out.writeInt(chunk.sourceEnd());
                    text(out, chunk.chunkerVersion());
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("CORPUS_DIGEST_UNAVAILABLE");
        }
    }

    private void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
