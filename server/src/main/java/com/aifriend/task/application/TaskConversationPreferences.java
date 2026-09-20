package com.aifriend.task.application;

import java.util.Set;

/**
 * 只允许进入任务语义草稿的三项有限个人偏好。
 *
 * <p>不包含 owner、联系人、微信号、消息、录音、转写或持久化元数据。
 * 它只能帮助模型生成待复述草稿，不能确认任务或生成动作计划。</p>
 *
 * @param speechRate 播报语速，只由终端 TTS 消费
 * @param dialogueStyle 追问详略，只由终端提示消费
 * @param ambiguousCall 含糊通话草稿偏好
 */
public record TaskConversationPreferences(
        String speechRate,
        String dialogueStyle,
        String ambiguousCall) {

    private static final Set<String> SPEECH_RATES = Set.of("SLOW", "NORMAL", "FAST");
    private static final Set<String> DIALOGUE_STYLES = Set.of("BRIEF", "STANDARD");
    private static final Set<String> AMBIGUOUS_CALLS = Set.of(
            "ASK_EVERY_TIME", "VOICE", "VIDEO");

    /** 校验只有协议允许的枚举能进入任务草稿层。 */
    public TaskConversationPreferences {
        if (!SPEECH_RATES.contains(speechRate)
                || !DIALOGUE_STYLES.contains(dialogueStyle)
                || !AMBIGUOUS_CALLS.contains(ambiguousCall)) {
            throw new IllegalArgumentException("任务个人偏好超出有限协议");
        }
    }

    /**
     * 返回任何缺失、关闭、撤权、损坏或读取失败时使用的安全默认值。
     *
     * @return 不自动选择通话类型的安全默认偏好
     */
    public static TaskConversationPreferences safeDefaults() {
        return new TaskConversationPreferences("NORMAL", "STANDARD", "ASK_EVERY_TIME");
    }
}