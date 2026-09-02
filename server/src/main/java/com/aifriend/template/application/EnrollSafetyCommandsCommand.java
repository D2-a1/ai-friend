package com.aifriend.template.application;

import java.util.List;

/**
 * 四类安全指令整批注册命令。
 *
 * @param commands 四类指令，每类恰好两遍录音
 * @param consentPolicyVersion 客户端明确同意的语音模板政策版本
 * @author Codex
 * @since 1.0.0
 */
public record EnrollSafetyCommandsCommand(
        List<SafetyCommandEnrollmentItem> commands,
        String consentPolicyVersion) {

    /** 创建带防御性列表副本的注册命令。 */
    public EnrollSafetyCommandsCommand {
        commands = commands == null ? null : List.copyOf(commands);
    }

    /**
     * 生成不含幂等键或原始音频的稳定请求指纹。
     *
     * @return 按类型排序的音频公开编号与政策版本组合
     */
    public String fingerprintInput() {
        return consentPolicyVersion + "\u001e"
                + commands.stream()
                        .map(SafetyCommandEnrollmentItem::fingerprintInput)
                        .reduce((left, right) -> left + "\u001e" + right)
                        .orElse("");
    }
}
