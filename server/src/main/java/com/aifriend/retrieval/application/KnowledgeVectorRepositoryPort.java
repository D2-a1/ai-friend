package com.aifriend.retrieval.application;

import java.util.Map;
import java.util.UUID;

import com.aifriend.retrieval.domain.IndexVersion;

/**
 * 完整generation向量读取边界，不允许按维度猜测模型空间。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeVectorRepositoryPort {
    /**
     * 在一致事务中验证generation/profile/维度/checksum并返回完整ID清单。
     * 返回数组须为本次读取独占，不可复用共享可变缓存。损坏/缺行须抛错。
     * @param version 精确世代及profile
     * @return 完整片段ID到向量映射
     */
    Map<UUID, float[]> load(IndexVersion version);
}
