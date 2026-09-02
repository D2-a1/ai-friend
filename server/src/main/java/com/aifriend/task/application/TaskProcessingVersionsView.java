package com.aifriend.task.application;

/**
 * 本次任务实际使用的全部处理版本。
 *
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param primaryAsrModelVersion 主 ASR 模型版本
 * @param mandarinAssistVersion 普通话辅助版本
 * @param fusionRuleVersion 融合规则版本
 * @param alignmentVersion 原声对齐版本
 * @param templateModelVersion 声学模板模型版本
 * @param thresholdVersion 声学阈值版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskProcessingVersionsView(
        String dialectCode,
        String dialectPackageVersion,
        String primaryAsrModelVersion,
        String mandarinAssistVersion,
        String fusionRuleVersion,
        String alignmentVersion,
        String templateModelVersion,
        String thresholdVersion) {
}
