package com.aifriend.retrieval.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 管理权限之后的公开文档元数据与逻辑失效边界，不包含原文或模型调用。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeDocumentManagementPort {
    /**
     * 读取当前文档根记录的有限状态。
     * @param id 文档ID
     * @return 当前根记录修订号，不是导入文档版本
     */
    Optional<DocumentState> find(UUID id);
    /**
     * 与导入/发布共用控制锁；清在途任务并推进语料修订，禁止旧worker发布。
     * 仅逻辑失效，不声称已物理擦除，功能开关关闭仍可调用。
     * @param id 文档ID
     * @param expectedRevision 已读取的根记录修订号
     */
    void invalidate(UUID id,long expectedRevision);
    /**
     * 文档条件删除使用的修订号快照。
     * @param id 文档ID
     * @param revision 当前根记录修订号
     * @param deleted 是否已逻辑删除
     */
    record DocumentState(UUID id,long revision,boolean deleted) {
        /** 校验元数据。 */
        public DocumentState {
            java.util.Objects.requireNonNull(id);
            if(revision<1) throw new IllegalArgumentException("INVALID_DOCUMENT_REVISION");
        }
    }
}
