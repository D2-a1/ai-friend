package com.aifriend.voice.domain;

/**
 * 受限音频对象用途。
 *
 * <p>休眠环境音不属于合法用途，也不得通过字符串扩展绕过本枚举。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AudioPurpose {
    /** 唤醒后当前任务音频。 */
    TASK,
    /** 联系人方言称呼双录样本。 */
    ALIAS_ENROLLMENT,
    /** 四类个人方言安全指令双录样本。 */
    SAFETY_COMMAND_ENROLLMENT,
    /** 封闭测试中经独立授权提交的语音采集样本。 */
    TEST_VOICE_COLLECTION
}
