package com.aifriend.template.application;

import java.util.List;

import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 日常指令动作片段运行时声学分类端口。
 *
 * <p>实现只比较发音内容，不输出说话人身份或声纹结论。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandRuntimeAcousticPort {

    /**
     * 在最多三十个 owner 模板中分类唯一动作片段。
     *
     * @param audio 已校验 TASK 音频
     * @param actionRange 已证明词边界的动作半开区间
     * @param templates 当前 owner 的兼容 ACTIVE 明文模板
     * @param clientContext 已验签客户端版本上下文
     * @return 无匹配、唯一意图或临界结果
     */
    RoutineCommandRuntimeMatch classify(
            ValidatedAudioObject audio,
            TaskAudioRange actionRange,
            List<RoutineCommandRuntimeTemplate> templates,
            TaskClientContext clientContext);
}
