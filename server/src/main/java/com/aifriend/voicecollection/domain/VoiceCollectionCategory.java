package com.aifriend.voicecollection.domain;

/** 封闭测试语音样本类别。 */
public enum VoiceCollectionCategory {
    /** 唤醒词。 */ WAKE_WORD,
    /** 联系人称呼。 */ CONTACT_ALIAS,
    /** 安全指令。 */ SAFETY_COMMAND,
    /** 完整任务口令。 */ FULL_TASK,
    /** 不应触发任务的负样本。 */ NEGATIVE
}
