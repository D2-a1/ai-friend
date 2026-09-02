package com.aifriend.contact.application;

/**
 * 已通过 API 结构校验的称呼创建命令。
 *
 * @param displayText 辅助展示文字，不参与唯一性
 * @param phoneticHint 可选音素提示，不参与唯一性裁决
 * @param firstAudioObjectId 第一遍 au_ 前缀音频对象编号
 * @param secondAudioObjectId 第二遍 au_ 前缀音频对象编号
 * @param expectedContactVersion 客户端展示的联系人对外版本
 * @param confirmed 用户已确认保存该称呼
 * @author Codex
 * @since 1.0.0
 */
public record CreateContactAliasCommand(
        String displayText,
        String phoneticHint,
        String firstAudioObjectId,
        String secondAudioObjectId,
        long expectedContactVersion,
        boolean confirmed) {

    /**
     * 生成当前调用内存中使用的稳定请求指纹输入。
     *
     * @return 包含请求语义但不得记录日志的指纹输入
     */
    public String fingerprintInput() {
        return displayText + '\u001f'
                + (phoneticHint == null ? "" : phoneticHint) + '\u001f'
                + firstAudioObjectId + '\u001f' + secondAudioObjectId + '\u001f'
                + expectedContactVersion + '\u001f' + confirmed;
    }

    /**
     * 返回不包含展示文字、提示或音频编号的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "CreateContactAliasCommand[expectedContactVersion="
                + expectedContactVersion + ", confirmed=" + confirmed + "]";
    }
}
