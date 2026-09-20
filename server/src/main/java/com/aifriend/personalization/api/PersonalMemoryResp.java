package com.aifriend.personalization.api;

import java.time.Instant;

/**
 * 当前长期个人偏好管理状态。
 *
 * @param featureEnabled 是否允许新增或更正
 * @param consentGranted 当前政策版本是否已同意
 * @param policyVersion 当前政策版本
 * @param preferences 当前有效偏好，未设置或已删除时为空
 * @param version 当前资源版本，未创建时为空
 * @param updatedAt 最近更新时间，未创建时为空
 * @author Codex
 * @since 1.0.0
 */
public record PersonalMemoryResp(
        boolean featureEnabled,
        boolean consentGranted,
        String policyVersion,
        PersonalMemoryPreferencesResp preferences,
        Long version,
        Instant updatedAt) {
}
