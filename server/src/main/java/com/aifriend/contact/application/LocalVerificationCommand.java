package com.aifriend.contact.application;

import java.time.Instant;

import com.aifriend.contact.domain.WechatPageType;

/**
 * 已完成 API 结构校验的本机联系人验证命令。
 *
 * @param stableLocator 最小稳定定位
 * @param currentRemark 当前微信备注，可空
 * @param pageType 当前微信页面类型
 * @param friendConfirmed 是否确认既有好友关系
 * @param locatorObservationCount 稳定定位观察次数
 * @param locatorUnique 稳定定位是否唯一
 * @param wechatVersion 当前微信版本
 * @param ruleVersion 本机验证规则版本
 * @param verifiedAt 客户端验证时间
 * @param expectedContactVersion 客户端看到的联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record LocalVerificationCommand(
        String stableLocator,
        String currentRemark,
        WechatPageType pageType,
        boolean friendConfirmed,
        int locatorObservationCount,
        boolean locatorUnique,
        String wechatVersion,
        String ruleVersion,
        Instant verifiedAt,
        long expectedContactVersion) {

    /**
     * 生成带字段长度的稳定请求指纹输入，区分相同幂等键下的不同正文。
     *
     * @return 不得写入日志的稳定指纹输入
     */
    public String fingerprintInput() {
        StringBuilder value = new StringBuilder();
        append(value, stableLocator);
        append(value, currentRemark);
        append(value, pageType.name());
        append(value, Boolean.toString(friendConfirmed));
        append(value, Integer.toString(locatorObservationCount));
        append(value, Boolean.toString(locatorUnique));
        append(value, wechatVersion);
        append(value, ruleVersion);
        append(value, verifiedAt.toString());
        append(value, Long.toString(expectedContactVersion));
        return value.toString();
    }

    private static void append(StringBuilder target, String field) {
        String value = field == null ? "" : field;
        target.append(value.length()).append(':').append(value).append(';');
    }
}
