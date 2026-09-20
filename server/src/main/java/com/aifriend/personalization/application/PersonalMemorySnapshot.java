package com.aifriend.personalization.application;

import java.time.Instant;

import com.aifriend.personalization.domain.PersonalMemoryPreferences;

/**
 * 当前用户可查看的长期偏好状态。
 *
 * @param featureEnabled 服务端是否允许新增或更正
 * @param consentGranted 当前政策版本是否已明确同意
 * @param policyVersion 当前政策版本
 * @param preferences 当前有效偏好，未设置或已删除时为空
 * @param version 当前资源版本，未创建时为空
 * @param updatedAt 最近更新时间，未创建时为空
 * @author Codex
 * @since 1.0.0
 */
public record PersonalMemorySnapshot(
        boolean featureEnabled,
        boolean consentGranted,
        String policyVersion,
        PersonalMemoryPreferences preferences,
        Long version,
        Instant updatedAt) {
}
