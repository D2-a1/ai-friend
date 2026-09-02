package com.aifriend.matcher.application;

import java.util.List;

import com.aifriend.matcher.domain.IntentSignal;

/**
 * 语义意图匹配扩展端口。
 *
 * <p>当前只稳定边界，没有默认实现。后续本地模型或云端适配器只能返回候选信号，
 * 不得直接执行消息或通话。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SemanticIntentMatcherPort {

    /**
     * 根据规范化文本生成语义候选信号。
     *
     * @param normalizedText 规范化后的输入文本
     * @return 候选信号，不包含执行授权
     */
    List<IntentSignal> match(String normalizedText);
}
