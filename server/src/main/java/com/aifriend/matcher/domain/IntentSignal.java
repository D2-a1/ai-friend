package com.aifriend.matcher.domain;

import java.util.Objects;

/**
 * 关键词意图信号。
 *
 * @param type 信号类型
 * @param keyword 命中的规范关键词
 * @param priority 安全优先级，数值越小越优先
 * @author Codex
 * @since 1.0.0
 */
public record IntentSignal(IntentSignalType type, String keyword, int priority) {

    /**
     * 创建并校验意图信号。
     *
     * @throws NullPointerException 当信号类型为空时抛出
     * @throws IllegalArgumentException 当关键词为空或优先级为负数时抛出
     */
    public IntentSignal {
        Objects.requireNonNull(type, "type must not be null");
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword must not be blank");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
    }
}
