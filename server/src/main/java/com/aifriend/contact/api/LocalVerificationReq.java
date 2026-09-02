package com.aifriend.contact.api;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aifriend.contact.domain.WechatPageType;

/**
 * 本机微信联系人验证的最小证据请求。
 *
 * @param stableLocator 当前目标联系人的最小稳定定位，只在请求内存中短暂存在
 * @param currentRemark 当前微信备注，可空且不参与唯一定位
 * @param pageType 当前微信页面类型
 * @param friendConfirmed 受支持规则是否确认目标为既有好友
 * @param locatorObservationCount 独立打开目标页面后的稳定定位观察次数
 * @param locatorUnique 当前页面的稳定定位是否唯一
 * @param wechatVersion 当前微信版本
 * @param ruleVersion 本机验证规则版本
 * @param verifiedAt 客户端完成页面验证的 UTC 时间
 * @param expectedContactVersion 客户端看到的联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record LocalVerificationReq(
        @NotBlank @Size(max = 512) String stableLocator,
        @Size(max = 80) String currentRemark,
        @NotNull WechatPageType pageType,
        boolean friendConfirmed,
        @Min(1) @Max(10) int locatorObservationCount,
        boolean locatorUnique,
        @NotBlank @Size(max = 40) String wechatVersion,
        @NotBlank @Size(max = 40) String ruleVersion,
        @NotNull Instant verifiedAt,
        @Min(1) long expectedContactVersion) {
}
