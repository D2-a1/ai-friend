package com.aifriend.template.application;

import java.util.List;

/**
 * 四类安全指令整批注册结果。
 *
 * @param complete 四类有效模板完整时固定为 true
 * @param templates 四类模板的最小元数据
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollmentResult(
        boolean complete,
        List<VoiceTemplateSummary> templates) {

    /** 创建带防御性列表副本的注册结果。 */
    public SafetyCommandEnrollmentResult {
        templates = List.copyOf(templates);
    }
}
