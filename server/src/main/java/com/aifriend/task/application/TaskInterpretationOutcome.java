package com.aifriend.task.application;

/**
 * 单次任务语音解释后的有限处理结果。
 *
 * <p>该结果独立于识别出的有限意图，用于在联系人匹配前决定是否允许继续。
 * 缺少消息内容时只允许重新录音或取消，不得进入候选、复述和确认链。
 *
 * @author codex
 * @since 1.0.0
 */
public enum TaskInterpretationOutcome {

    /** 意图和必要内容完整，可以继续安全匹配。 */
    READY,

    /** 消息接收人可能存在，但消息正文缺失，必须针对性重说。 */
    NEEDS_CONTENT_REPEAT,

    /** 纠正残缺、动作冲突或语义不完整，必须重说完整任务。 */
    NEEDS_RETRY,

    /** 用户明确取消，本次任务直接结束。 */
    CANCELLED
}
