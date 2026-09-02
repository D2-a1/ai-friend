package com.aifriend.contact.application;

import java.time.Instant;
import java.util.List;

import com.aifriend.contact.domain.ContactStatus;

/**
 * 不含微信主体、定位或聊天数据的联系人最小展示结果。
 *
 * @param id ct_ 前缀公开联系人编号
 * @param displayName 当前最小展示名称，尚未同步时为空
 * @param remark 当前微信备注，尚未验证时为空
 * @param avatarUrl 当前头像地址，尚未同步时为空
 * @param relationship 可选关系说明
 * @param status 联系人状态
 * @param aliasCount 有效称呼数量
 * @param aliases 有效称呼最小展示列表
 * @param localVerificationVersion 本机验证规则版本，可空
 * @param verifiedAt 最近本机验证时间，可空
 * @param version 对外从 1 开始的版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author Codex
 * @since 1.0.0
 */
public record ContactSummary(
        String id,
        String displayName,
        String remark,
        String avatarUrl,
        String relationship,
        ContactStatus status,
        int aliasCount,
        List<ContactAliasSummary> aliases,
        String localVerificationVersion,
        Instant verifiedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** 固化称呼展示列表并校验计数一致。 */
    public ContactSummary {
        aliases = List.copyOf(aliases);
        if (aliasCount != aliases.size()) {
            throw new IllegalArgumentException("称呼数量与列表不一致");
        }
    }

    /**
     * 创建不携带称呼的兼容联系人摘要。
     *
     * @param id ct_ 前缀公开联系人编号
     * @param displayName 展示名称，可空
     * @param remark 微信备注，可空
     * @param avatarUrl 头像地址，可空
     * @param relationship 关系说明，可空
     * @param status 联系人状态
     * @param aliasCount 必须为 0
     * @param localVerificationVersion 本机验证规则版本，可空
     * @param verifiedAt 最近验证时间，可空
     * @param version 对外版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public ContactSummary(
            String id,
            String displayName,
            String remark,
            String avatarUrl,
            String relationship,
            ContactStatus status,
            int aliasCount,
            String localVerificationVersion,
            Instant verifiedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(id, displayName, remark, avatarUrl, relationship, status,
                aliasCount, List.of(), localVerificationVersion, verifiedAt,
                version, createdAt, updatedAt);
    }
}
