package com.aifriend.task.domain;

/** 同一语音会话中本轮录音的语义。 */
public enum TaskRevisionMode {
    /** 首轮没听清或缺少必需槽位，重说完整需求。 */
    FULL_RETRY,
    /** 系统复述后，只修改用户明确指出的联系人、动作或内容。 */
    CORRECTION
}