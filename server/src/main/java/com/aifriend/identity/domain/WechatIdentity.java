package com.aifriend.identity.domain;

/**
 * 微信身份适配器返回的最小主体。
 *
 * <p>主体只在登录调用内存中短暂存在，不允许记录日志或返回客户端。
 *
 * @param subject 微信应用作用域内稳定主体
 * @author Codex
 * @since 1.0.0
 */
public record WechatIdentity(String subject) {
}
