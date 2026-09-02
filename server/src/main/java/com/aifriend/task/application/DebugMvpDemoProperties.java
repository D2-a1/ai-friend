package com.aifriend.task.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缺少真实微信资料时的 Debug MVP 演示开关。
 *
 * <p>开启后只允许合成体验联系人进入模拟完成终态，不生成微信动作计划，
 * 不调用微信，也不改变真实联系人和 Release 的失败关闭边界。
 *
 * @param enabled 是否启用 Debug MVP 演示链
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.debug-mvp-demo")
public record DebugMvpDemoProperties(boolean enabled) {
}
