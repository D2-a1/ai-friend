package com.aifriend.dialect.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 方言包加载与信任根配置。
 *
 * <p>方言语音能力默认关闭。公钥不是秘密，但必须由部署配置提供并与方言包中的
 * {@code signatureKeyId} 一致；配置不完整或包校验失败时只停用方言能力，不影响账号与数据管理。
 *
 * @param enabled 是否尝试加载当前随应用发布的方言包
 * @param requiredDialectCode 当前版本唯一允许激活的主方言代码
 * @param packageRoot 方言包资源根目录，支持 classpath 或 file 资源
 * @param trustedKeyId 受信任 Ed25519 公钥编号
 * @param trustedPublicKeyBase64 X.509 编码 Ed25519 公钥的 Base64
 * @param serverVersion 当前服务端语义版本，用于最低兼容版本校验
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.dialect")
public record DialectPackageProperties(
        boolean enabled,
        String requiredDialectCode,
        String packageRoot,
        String trustedKeyId,
        String trustedPublicKeyBase64,
        String serverVersion) {
}
