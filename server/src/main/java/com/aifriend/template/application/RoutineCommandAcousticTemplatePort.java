package com.aifriend.template.application;

import java.util.List;
import java.util.Optional;

/**
 * 日常动作单段 MFCC 提取和同意图重复合并判定端口。
 *
 * <p>实现只比较发音内容，不输出说话人身份或声纹分数；正式签名方言包缺失、
 * 版本不符或范围不可靠时必须失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandAcousticTemplatePort {

    /**
     * 从 Outbox 固化的唯一动作范围提取单段模板。
     *
     * @param job 已取得租约的学习任务
     * @param audio 已完整复验的短期 TASK 音频
     * @return 需要由调用方关闭并清零的模板候选
     */
    RoutineCommandAcousticCandidate extract(
            RoutineCommandLearningJob job,
            RoutineCommandAudioSnapshot audio);

    /**
     * 在同一意图既有模板中寻找唯一最近项，并使用签名包一致性阈值决定是否合并。
     *
     * @param candidate 新提取候选
     * @param existingTemplates 最多三十个短期解密模板
     * @param job 固化方言与模型版本的学习任务
     * @return 可合并模板；不存在时应创建新模板
     */
    Optional<RoutineCommandTemplateMatch> findMergeTarget(
            RoutineCommandAcousticCandidate candidate,
            List<RoutineCommandPlainTemplate> existingTemplates,
            RoutineCommandLearningJob job);
}
