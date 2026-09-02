package com.aifriend.dialect.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 无正式武冈话包时的个人基础体验开关。
 *
 * <p>该模式只允许使用固定普通话参考模型和个人声学模板验证基础链路，
 * 不得对外宣称为武冈话模型，也不得替代后续训练、验签和发布流程。
 *
 * @param enabled 是否启用个人基础体验链
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.basic-experience")
public record BasicExperienceProperties(boolean enabled) {
}
