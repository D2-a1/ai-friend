package com.aifriend.voicecollection.application;

/**
 * 本机训练输入的数据集分区。
 *
 * @author codex
 * @since 1.0.0
 */
public enum VoiceTrainingInputSplit {
    /** 参与参数更新的训练集。 */
    TRAIN,
    /** 只用于离线验证的验证集。 */
    VALIDATION
}
