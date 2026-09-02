package com.aifriend.contact.application;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 本机微信联系人验证的失败关闭配置。
 *
 * @param evidenceMaxAge 客户端页面证据最大有效时长
 * @param futureClockSkew 允许的客户端时钟向未来偏移量
 * @param allowedRulePairs 已验证的规则配置，继续使用 wechatVersion:ruleVersion 兼容格式；
 * 微信版本仅作诊断，放行只匹配规则版本
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.contact.local-verification")
public record ContactVerificationProperties(
        @NotNull Duration evidenceMaxAge,
        @NotNull Duration futureClockSkew,
        @NotNull List<String> allowedRulePairs) {

    /**
     * 固化配置并拒绝无效时间边界。
     */
    public ContactVerificationProperties {
        allowedRulePairs = List.copyOf(allowedRulePairs);
        if (evidenceMaxAge.isZero() || evidenceMaxAge.isNegative()
                || futureClockSkew.isNegative()) {
            throw new IllegalArgumentException("本机验证证据时限配置无效");
        }
    }

    /**
     * 判断规则版本是否已完成白名单验证。
     *
     * <p>配置继续保留 {@code wechatVersion:ruleVersion} 结构，避免改变既有
     * {@code server.env}；其中微信版本只作诊断，不参与放行。
     *
     * @param wechatVersion 当前微信版本，仅用于调用契约兼容和诊断
     * @param ruleVersion 本机验证规则版本
     * @return 规则版本位于白名单时返回 true；空白名单固定失败关闭
     */
    public boolean supports(String wechatVersion, String ruleVersion) {
        if (ruleVersion == null || ruleVersion.isBlank() || ruleVersion.contains(":")) {
            return false;
        }
        return allowedRulePairs.stream()
                .anyMatch(rulePair -> matchesRuleVersion(rulePair, ruleVersion));
    }

    private static boolean matchesRuleVersion(String rulePair, String ruleVersion) {
        int separatorIndex = rulePair.indexOf(':');
        return separatorIndex > 0
                && separatorIndex == rulePair.lastIndexOf(':')
                && separatorIndex < rulePair.length() - 1
                && rulePair.substring(separatorIndex + 1).equals(ruleVersion);
    }
}
