package com.aifriend.retrieval.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;

/**
 * 在持久有效租约内读取完整公开构建源；不复用不完整旧索引作为来源。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeBuildSourcePort {
    /**
     * 新事务锁定控制行，核对当前job/profile并读取活动来源加本次目标版本。
     * @param claim 当前有效认领
     * @return 有界完整来源，不包含其他待处理job的新版本
     */
    Source load(Claim claim);

    /**
     * 当前构建的不可变来源集合；变更后发布必须再次复验。
     * @param claim 认领材料
     * @param specification 固定算法与模型空间
     * @param targetDocument 本任务更改的文档
     * @param targetVersion 本任务更改的版本
     * @param documents 全部应进入新世代的公开文档
     */
    record Source(Claim claim, BuildSpecification specification, UUID targetDocument,
            long targetVersion, List<KnowledgeDocument> documents) {
        /** 防止遗漏目标、重复来源或超过全量清单限额。 */
        public Source {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(specification, "specification");
            Objects.requireNonNull(targetDocument, "targetDocument");
            if (targetVersion < 1 || documents == null || documents.isEmpty() || documents.size() > 100) {
                throw new IllegalArgumentException("INVALID_BUILD_SOURCE");
            }
            documents = List.copyOf(documents);
            var ids = new HashSet<UUID>();
            var keys = new HashSet<String>();
            boolean targetFound = false;
            for (var document : documents) {
                if (!ids.add(document.id()) || !keys.add(document.sourceKey())) {
                    throw new IllegalArgumentException("DUPLICATE_BUILD_SOURCE");
                }
                if (document.id().equals(targetDocument) && document.version() == targetVersion) { targetFound = true; }
            }
            if (!targetFound) { throw new IllegalArgumentException("MISSING_BUILD_TARGET"); }
        }

        /**
         * 把切分结果绑定到完整源，拒绝缺字、重复片段或跨算法版本。
         * @param generation 本次新世代标识
         * @param chunks 对全部documents的完整分块
         * @return 可进一步暂存但尚未发布的快照
         */
        public KnowledgeRepositoryPort.Snapshot snapshot(UUID generation, List<KnowledgeChunk> chunks) {
            var version = new IndexVersion(generation, claim.corpusRevision(), specification.embeddingProfile(),
                    specification.tokenizerVersion(), specification.chunkerVersion());
            return new KnowledgeRepositoryPort.Snapshot(version, documents, chunks);
        }
        @Override public String toString() { return "Source[documents=" + documents.size() + ", content=redacted]"; }
    }
}
