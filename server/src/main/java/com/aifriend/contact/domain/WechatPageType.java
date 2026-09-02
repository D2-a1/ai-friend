package com.aifriend.contact.domain;

/**
 * Android 本机验证识别到的微信页面类型。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum WechatPageType {
    /** 单聊联系人的资料页。 */
    CONTACT_PROFILE,
    /** 已唯一确认目标联系人的单聊页。 */
    DIRECT_CHAT,
    /** 页面加载中、群聊、搜索或其他不允许验证的页面。 */
    UNSUPPORTED
}
