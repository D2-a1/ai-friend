package com.aifriend.task.application;

/**
 * 不持久化的词级识别时间戳。
 *
 * @param text 临时词文本，不得记录日志
 * @param startMs 起始毫秒，包含
 * @param endMs 结束毫秒，不包含
 * @param confidence 词置信度，范围 0—1
 * @author Codex
 * @since 1.0.0
 */
public record TaskRecognizedWord(
        String text,
        int startMs,
        int endMs,
        double confidence) {
}
