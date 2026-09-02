package com.aifriend.task.application;

import java.time.Instant;

/**
 * Ed25519 定位证明必须绑定的动作计划声明。
 *
 * @param planId 当前计划编号
 * @param action 有限微信动作
 * @param contactId ct_ 前缀联系人编号
 * @param audioObjectId 消息音频编号，可空
 * @param summaryHash 当前完整复述摘要哈希
 * @param minimumRuleVersion 客户端最低规则版本
 * @param contactVersion 联系人聚合版本
 * @param wechatVersion 当前任务客户端上下文中的微信版本
 * @param locatorVersion 稳定定位规则版本
 * @param issuedAt 证明签发时间
 * @param expiresAt 计划与证明共同过期时间
 * @author Codex
 * @since 1.0.0
 */
public record WechatActionPlanProofClaims(
        String planId,
        String action,
        String contactId,
        String audioObjectId,
        String summaryHash,
        String minimumRuleVersion,
        long contactVersion,
        String wechatVersion,
        String locatorVersion,
        Instant issuedAt,
        Instant expiresAt) {
}
