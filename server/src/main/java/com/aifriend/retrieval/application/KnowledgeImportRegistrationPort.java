package com.aifriend.retrieval.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.KnowledgeImportRequest;

/**
 * 公开知识导入的幂等短事务登记；只允许已通过独立管理权限的入口调用。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeImportRegistrationPort {
    /**
     * 同键同完整请求返回同job；登记不进行分块、模型调用或索引发布。
     * @param request 已验证原文与幂等键
     * @param specification 固定算法/向量空间
     * @return 持久受理事实
     */
    Receipt register(KnowledgeImportRequest request, BuildSpecification specification);

    /**
     * 查询导入事实，不宣称READY仍有当前活动版本；已删除来源不得重放成功。
     * @param id 任务标识
     * @return 未找到时empty，数据库故障抛出
     */
    Optional<Receipt> find(UUID id);

    /**
     * 固定构建profile；同维不同模型不可视为同一请求。
     * @param embeddingProfile 词法构建为empty
     * @param tokenizerVersion 分词器版本
     * @param chunkerVersion 分块器版本
     */
    record BuildSpecification(Optional<EmbeddingProfile> embeddingProfile, String tokenizerVersion, String chunkerVersion) {
        /** 复用索引算法版本校验。 */
        public BuildSpecification {
            new IndexVersion(new UUID(0, 0), 0, embeddingProfile, tokenizerVersion, chunkerVersion);
        }
    }

    /**
     * 不包含原文、模型地址、幂等键或租约的受理响应。
     * @param id 任务标识
     * @param documentId 公开来源标识
     * @param documentVersion 本任务目标版本
     * @param state 持久任务状态
     * @param attempts 总认领次数
     * @param failure 固定错误码
     */
    record Receipt(UUID id, UUID documentId, long documentVersion, KnowledgeImportJob.State state,
            int attempts, KnowledgeImportJob.Failure failure) {
        /** 防止把损坏任务元数据作为成功响应。 */
        public Receipt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(failure, "failure");
            if (documentVersion < 1 || attempts < 0 || attempts > KnowledgeImportJob.MAX_ATTEMPTS
                    || (state == KnowledgeImportJob.State.READY && (attempts == 0 || failure != KnowledgeImportJob.Failure.NONE))
                    || (state == KnowledgeImportJob.State.FAILED && failure == KnowledgeImportJob.Failure.NONE)) {
                throw new IllegalArgumentException("INVALID_IMPORT_RECEIPT");
            }
        }
    }
}
