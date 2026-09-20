package com.aifriend.task.application;

import java.util.List;

import com.aifriend.task.domain.TaskIntent;

/**
 * 有限通信动作短语目录。
 *
 * <p>关键词解析与日常指令学习共用同一动作词源。学习仅接受完整落在词级时间戳
 * 边界上的明确短语；默认消息意图、联系人词和消息正文不能生成学习证据。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class TaskActionPhraseCatalog {

    private static final List<String> VIDEO_CALL_PHRASES = List.of(
            "视频电话", "视频通话", "打视频");
    private static final List<String> VOICE_CALL_PHRASES = List.of(
            "语音电话", "语音通话", "打语音", "打电话", "通话");
    private static final List<String> MESSAGE_PHRASES = List.of(
            "发消息", "发信息", "发给", "告诉", "跟他说", "跟她说", "叫", "喊");
    private static final List<String> MESSAGE_LEARNING_PHRASES = List.of(
            "发消息", "发信息", "发给", "告诉", "跟他说", "跟她说");

    private TaskActionPhraseCatalog() {
    }

    /**
     * 返回视频通话关键词。
     *
     * @return 不可变视频通话短语
     */
    public static List<String> videoCallPhrases() {
        return VIDEO_CALL_PHRASES;
    }

    /**
     * 返回语音通话关键词。
     *
     * @return 不可变语音通话短语
     */
    public static List<String> voiceCallPhrases() {
        return VOICE_CALL_PHRASES;
    }

    /**
     * 返回消息动作关键词。
     *
     * @return 不可变消息短语
     */
    public static List<String> messagePhrases() {
        return MESSAGE_PHRASES;
    }

    /**
     * 返回允许建立日常指令声学模板的明确动作短语。
     *
     * <p>“叫”“喊”等单字虽然可参与有限意图候选，但容易与联系人或正文混合，
     * 不具备稳定学习边界，因此不进入本列表。
     *
     * @param intent 已完成保守解析的有限通信意图
     * @return 对应意图的不可变学习短语；非通信意图返回空列表
     */
    public static List<String> routineLearningPhrases(TaskIntent intent) {
        if (intent == null) {
            return List.of();
        }
        return switch (intent) {
            case SEND_MESSAGE -> MESSAGE_LEARNING_PHRASES;
            case VOICE_CALL -> VOICE_CALL_PHRASES;
            case VIDEO_CALL -> VIDEO_CALL_PHRASES;
            default -> List.of();
        };
    }
}
