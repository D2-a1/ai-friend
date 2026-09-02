package com.aifriend.task.application;

/**
 * 创建任务时固化的客户端规则和语音模型版本。
 *
 * @param appVersion App 版本
 * @param wechatVersion 微信版本
 * @param ruleVersion 受限微信规则版本
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param mandarinAssistVersion 普通话辅助模型版本
 * @param fusionRuleVersion 融合规则版本
 * @param templateModelVersion 本地声学模板模型版本
 * @param thresholdVersion 本地声学阈值版本
 * @param basicRecognition 可空的 Android 本机基础识别证据
 * @author Codex
 * @since 1.0.0
 */
public record TaskClientContext(
        String appVersion,
        String wechatVersion,
        String ruleVersion,
        String dialectCode,
        String dialectPackageVersion,
        String mandarinAssistVersion,
        String fusionRuleVersion,
        String templateModelVersion,
        String thresholdVersion,
        TaskClientRecognitionEvidence basicRecognition) {

    /**
     * 创建不携带基础识别证据的兼容上下文。
     *
     * @param appVersion App 版本
     * @param wechatVersion 微信版本
     * @param ruleVersion 受限微信规则版本
     * @param dialectCode 方言代码
     * @param dialectPackageVersion 方言包版本
     * @param mandarinAssistVersion 普通话辅助模型版本
     * @param fusionRuleVersion 融合规则版本
     * @param templateModelVersion 本地声学模板模型版本
     * @param thresholdVersion 本地声学阈值版本
     */
    public TaskClientContext(
            String appVersion,
            String wechatVersion,
            String ruleVersion,
            String dialectCode,
            String dialectPackageVersion,
            String mandarinAssistVersion,
            String fusionRuleVersion,
            String templateModelVersion,
            String thresholdVersion) {
        this(appVersion, wechatVersion, ruleVersion, dialectCode,
                dialectPackageVersion, mandarinAssistVersion, fusionRuleVersion,
                templateModelVersion, thresholdVersion, null);
    }

    /**
     * 生成不含用户正文的稳定请求指纹片段。
     *
     * @return 版本字段拼接结果
     */
    public String fingerprintInput() {
        String versions = String.join("|", appVersion, wechatVersion, ruleVersion,
                dialectCode, dialectPackageVersion, mandarinAssistVersion,
                fusionRuleVersion, templateModelVersion, thresholdVersion);
        return basicRecognition == null ? versions
                : versions + "|" + basicRecognition.fingerprintInput();
    }
}
