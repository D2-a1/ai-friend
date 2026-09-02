package com.aifriend.task.application;

import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * owner 范围日常指令声学模板运行时复核端口。
 *
 * <p>实现只能使用已验签方言包和当前 owner 的 ACTIVE 模板。结果只参与有限意图
 * 冲突收敛，不能替代联系人匹配、完整复述或个人动作型确认。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskRoutineCommandMatcherPort {

    /**
     * 使用已证明的动作原声范围复核转写有限意图。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param audio 已校验但尚未消费的 TASK 音频
     * @param clientContext 客户端已验签方言包版本上下文
     * @param transcriptIntent 本地有限关键词得到的动作意图
     * @param learningEvidence 可空的唯一动作原声范围
     * @return 不适用、无匹配、已复核或冲突结果
     */
    TaskRoutineCommandMatchDecision match(
            UUID ownerUserId,
            ValidatedAudioObject audio,
            TaskClientContext clientContext,
            TaskIntent transcriptIntent,
            RoutineCommandLearningEvidence learningEvidence);
}
