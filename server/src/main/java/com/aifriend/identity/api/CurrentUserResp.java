package com.aifriend.identity.api;

/**
 * 当前登录用户响应。
 *
 * @param id us_ 前缀用户编号
 * @param status 账号状态
 * @param displayName 展示名；未完成必要授权时为空
 * @author Codex
 * @since 1.0.0
 */
public record CurrentUserResp(String id, String status, String displayName) {
}
