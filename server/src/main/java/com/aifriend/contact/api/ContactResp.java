package com.aifriend.contact.api;

import java.time.Instant;
import java.util.List;

import com.aifriend.contact.domain.ContactStatus;

/**
 * 联系人最小展示响应。
 *
 * @param id ct_ 前缀联系人编号
 * @param displayName 展示名称，可空
 * @param remark 微信备注，可空
 * @param avatarUrl 头像地址，可空且不参与身份判断
 * @param relationship 关系说明，可空
 * @param status 联系人状态
 * @param aliasCount 有效称呼数量
 * @param aliases 有效称呼列表
 * @param localVerificationVersion 本机验证规则版本，可空
 * @param verifiedAt 最近验证时间，可空
 * @param version 对外版本，从 1 开始
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author Codex
 * @since 1.0.0
 */
public record ContactResp(
        String id,
        String displayName,
        String remark,
        String avatarUrl,
        String relationship,
        ContactStatus status,
        int aliasCount,
        List<ContactAliasResp> aliases,
        String localVerificationVersion,
        Instant verifiedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 固化称呼列表。
     */
    public ContactResp {
        aliases = List.copyOf(aliases);
    }
}
