package com.aifriend.template.api;

import java.util.List;

/**
 * 四类安全指令整批注册响应 DTO。
 *
 * @param complete 四类有效模板完整时为 true
 * @param templates 恰好四类安全指令模板元数据
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollmentResp(
        boolean complete,
        List<VoiceTemplateResp> templates) {

    /** 创建带防御性列表副本的响应。 */
    public SafetyCommandEnrollmentResp {
        templates = List.copyOf(templates);
    }
}
