package com.aifriend.task.application;

import com.aifriend.task.domain.TaskIntent;

/**
 * 本地优先的有限通信意图解析端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskIntentPort {

    /**
     * 将转写解析为候选意图；结果不能直接执行微信动作。
     *
     * @param transcript 临时转写，不得记录日志
     * @return 有限意图与是否必须重说的决定
     */
    TaskIntentDecision parse(String transcript);
}
