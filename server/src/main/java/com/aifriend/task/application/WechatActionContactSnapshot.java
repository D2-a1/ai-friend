package com.aifriend.task.application;

import java.util.UUID;

/**
 * 为当前动作计划短暂读取的 owner 范围联系人定位快照。
 *
 * <p>稳定定位明文只允许用于当前调用内存中的盐化摘要，禁止持久化、日志或响应返回。
 *
 * @param contactId 联系人 UUID
 * @param contactVersion 联系人聚合版本
 * @param stableLocator 已完成本机验证的规范化稳定定位明文
 * @param wechatVersion 历史本机验证微信版本，仅用于诊断且邀请绑定时可空
 * @param locatorVersion 稳定定位提取规则版本
 * @author Codex
 * @since 1.0.0
 */
public record WechatActionContactSnapshot(
        UUID contactId,
        long contactVersion,
        String stableLocator,
        String wechatVersion,
        String locatorVersion) {

    /**
     * 返回不包含稳定定位或联系人编号的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "WechatActionContactSnapshot[contactVersion=" + contactVersion
                + ", wechatVersion=" + wechatVersion + ", locatorVersion="
                + locatorVersion + ", protectedFields=<redacted>]";
    }
}
