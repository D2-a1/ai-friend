package com.aifriend.task.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信动作计划定位证明签名配置。
 *
 * <p>私钥只允许由环境或密钥管理系统注入；缺失或无效时应用仍可提供数据管理能力，
 * 但动作计划签发必须失败关闭。
 *
 * @param keyId Ed25519 签名密钥编号
 * @param privateKeyPkcs8Base64 PKCS#8 Ed25519 私钥 Base64
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.wechat.action-plan-signing")
public record WechatActionPlanSigningProperties(
        String keyId,
        String privateKeyPkcs8Base64) {
}
