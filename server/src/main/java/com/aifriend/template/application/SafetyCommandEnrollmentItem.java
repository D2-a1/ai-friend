package com.aifriend.template.application;

import com.aifriend.template.domain.SafetyCommandType;

/**
 * 一类安全指令的两遍录音注册命令。
 *
 * @param type 固定安全指令类型
 * @param firstAudioObjectId 第一遍 au_ 音频对象编号
 * @param secondAudioObjectId 第二遍 au_ 音频对象编号
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollmentItem(
        SafetyCommandType type,
        String firstAudioObjectId,
        String secondAudioObjectId) {

    /**
     * 生成不含原始音频的稳定请求指纹片段。
     *
     * @return 安全指令类型与两个公开音频编号的组合
     */
    public String fingerprintInput() {
        return type.name() + "\u001f" + firstAudioObjectId + "\u001f" + secondAudioObjectId;
    }
}
