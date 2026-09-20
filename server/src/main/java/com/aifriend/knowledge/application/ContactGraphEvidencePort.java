package com.aifriend.knowledge.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphSnapshot;

/**
 * 可复用显示读取内部前后快照的权威来源扩展，不缓存跨请求证明。
 * @author Codex
 * @since 1.0.0
 */
public interface ContactGraphEvidencePort extends ContactGraphSourcePort {
    /**
     * 在新事务校验来源并读取显示，再以另一个新事务复验；不得复用旧隔离视图。
     * @param owner 当前主体
     * @param expectedDigest 原来源摘要
     * @param contactIds 至多二十个唯一候选
     * @return 显示读取前后的完整事实和当前显示，调用者仍验证身份、事实及候选完整性
     */
    Evidence displayEvidence(UUID owner, String expectedDigest, List<UUID> contactIds);

    /**
     * 仅用于当前调用的证据，不得作为后续请求的授权凭据。
     * @param before 解密所在事务内读取的来源事实
     * @param after 解密后独立新事务读取的来源事实
     * @param displays 当前完整显示列表
     */
    record Evidence(GraphSnapshot before, GraphSnapshot after, List<ContactDisplay> displays) {
        /** 拷贝结果集合，拒绝缺失证明。 */
        public Evidence {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            displays = List.copyOf(displays);
        }
        /** 不在默认日志中泄露联系人引用或称呼。 */
        @Override public String toString() { return "GraphDisplayEvidence[redacted]"; }
    }
}
