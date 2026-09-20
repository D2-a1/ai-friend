package com.aifriend.task.application;

import com.aifriend.task.domain.TaskRevisionMode;

/**
 * 有上下文的任务草稿理解端口。
 *
 * <p>实现可以是端侧模型、本地服务模型或经明确授权的云模型。无论实现为何，
 * 都只能返回有限字段补丁，不能跳过联系人复验、完整复述、最新版本确认或签名计划。
 */
public interface TaskConversationUnderstandingPort {

    /**
     * 根据当前加密草稿和本轮语音生成有限修订。
     *
     * @param current 当前解密到内存的任务载荷；首轮完整需求为空
     * @param recognition 本轮临时语音识别证据
     * @param actualDurationMs 本轮录音真实时长
     * @param mode 完整重说或定向纠错
     * @return 有限结构化补丁
     */
    default TaskDraftRevision revise(
            TaskPayload current,
            TaskSpeechRecognition recognition,
            int actualDurationMs,
            TaskRevisionMode mode) {
        return revise(current, recognition, actualDurationMs, mode,
                TaskConversationPreferences.safeDefaults());
    }

    /**
     * 带低权限有限偏好修订草稿。偏好不包含 owner 或联系人，
     * 不得改变确认、联系人复验或动作计划边界。
     *
     * @param current 当前草稿
     * @param recognition 当前临时识别证据
     * @param actualDurationMs 当前录音时长
     * @param mode 修订方式
     * @param preferences 三项有限偏好或安全默认值
     * @return 有限草稿补丁
     */
    TaskDraftRevision revise(
            TaskPayload current,
            TaskSpeechRecognition recognition,
            int actualDurationMs,
            TaskRevisionMode mode,
            TaskConversationPreferences preferences);
}