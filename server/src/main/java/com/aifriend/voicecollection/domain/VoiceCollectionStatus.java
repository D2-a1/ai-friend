package com.aifriend.voicecollection.domain;

/** 采集样本对用户可见的最小状态。 */
public enum VoiceCollectionStatus {
    /** 已保存并处于三十天留存窗口。 */ ACTIVE,
    /** 已停止使用并等待物理删除。 */ DELETE_REQUESTED
}
