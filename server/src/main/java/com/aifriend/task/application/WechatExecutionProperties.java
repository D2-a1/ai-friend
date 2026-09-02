package com.aifriend.task.application;

import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 微信有限动作计划的服务端失败关闭配置。
 *
 * <p>只有总开关开启且客户端上报的 App 与规则版本精确命中已完成验收的组合，
 * 服务端才允许继续签发通话动作计划；消息动作还必须精确命中组合中的微信版本。
 * 空白名单、未知动作、未知版本和部分匹配均拒绝。
 *
 * @param enabled 是否允许签发微信有限动作计划，默认必须为 false
 * @param approvedClientCombinations 已完成真机验收的客户端版本组合
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.wechat.execution")
public record WechatExecutionProperties(
        boolean enabled,
        @NotNull List<@Valid ApprovedClientCombination> approvedClientCombinations) {

    /**
     * 固化版本白名单，避免运行期配置集合被修改。
     */
    public WechatExecutionProperties {
        approvedClientCombinations = List.copyOf(
                Objects.requireNonNull(approvedClientCombinations,
                        "微信执行版本白名单不能为空"));
    }

    /**
     * 判断当前任务固化的客户端版本是否允许签发动作计划。
     *
     * <p>语音和视频通话只要求 App、规则版本精确命中；消息动作还必须让当前
     * 微信版本精确命中验收组合。未知动作固定拒绝。
     *
     * @param context 创建任务时固化的客户端版本上下文
     * @param action 当前有限微信动作
     * @return 总开关和当前动作对应的版本门禁全部通过时返回 true
     */
    public boolean supports(TaskClientContext context, String action) {
        boolean callAction = "START_VOICE_CALL".equals(action)
                || "START_VIDEO_CALL".equals(action);
        boolean messageAction = "SEND_AUDIO_AND_TEXT".equals(action);
        if (!enabled || context == null || (!callAction && !messageAction)) {
            return false;
        }
        if (callAction
                && !WechatSemanticCallContract.RULE_VERSION.equals(context.ruleVersion())) {
            return false;
        }
        return approvedClientCombinations.stream().anyMatch(combination ->
                combination.appVersion().equals(context.appVersion())
                        && combination.ruleVersion().equals(context.ruleVersion())
                        && (callAction || combination.wechatVersion()
                                .equals(context.wechatVersion())));
    }

    /**
     * 已完成验收的 App、微信及签名规则版本组合。
     *
     * @param appVersion Android App 版本，必须精确匹配
     * @param wechatVersion 消息动作必须精确匹配；通话动作仅用于诊断和配置兼容
     * @param ruleVersion 随 APK 签名规则版本，必须精确匹配
     * @author Codex
     * @since 1.0.0
     */
    public record ApprovedClientCombination(
            String appVersion,
            String wechatVersion,
            String ruleVersion) {

        private static final String VERSION_TOKEN_PATTERN = "[^\\s:]{1,100}";

        /**
         * 拒绝空白、超长或可破坏三元组边界的版本字段。
         */
        public ApprovedClientCombination {
            requireVersionToken(appVersion);
            requireVersionToken(wechatVersion);
            requireVersionToken(ruleVersion);
        }

        private static void requireVersionToken(String value) {
            if (value == null || !value.matches(VERSION_TOKEN_PATTERN)) {
                throw new IllegalArgumentException("微信执行版本组合无效");
            }
        }
    }
}
