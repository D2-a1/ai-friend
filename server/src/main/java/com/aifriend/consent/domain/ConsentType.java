package com.aifriend.consent.domain;

/**
 * OpenAPI 定义的分项授权类型。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum ConsentType {
    /** 创建账号和维持登录所需的基础身份处理。 */
    BASIC_IDENTITY,
    /** 使用麦克风采集当前任务语音。 */
    MICROPHONE,
    /** 发送守护状态和任务结果通知。 */
    NOTIFICATION,
    /** 使用受限无障碍服务辅助微信操作。 */
    ACCESSIBILITY,
    /** 保存个人称呼和安全指令声学模板。 */
    VOICE_TEMPLATE,
    /** 处理唤醒后的当前任务音频。 */
    TASK_AUDIO,
    /** 在封闭测试中保存用于改进方言能力的独立语音样本。 */
    TEST_VOICE_COLLECTION,
    /** 允许把已明确选择的封闭测试样本用于模型训练。 */
    VOICE_MODEL_TRAINING
}
