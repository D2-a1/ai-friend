package com.aifriend.retrieval.domain;

import java.nio.charset.StandardCharsets;

/**
 * 知识文本的有界 Unicode 校验；不对来源原文执行规范化。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeText {
    private KnowledgeText() { }

    /**
     * 验证完整代理对、文本控制符及 code point/UTF-8 字节预算。
     * @param text 原文
     * @param maxCodePoints 最大码点数
     * @param maxBytes 最大 UTF-8 字节数
     * @param allowBlank 是否允许空白
     * @return 未改动的原文
     */
    public static String require(String text, int maxCodePoints, int maxBytes, boolean allowBlank) {
        if (text == null || maxCodePoints < 1 || maxBytes < 1
                || text.length() > (long) maxCodePoints * 2L) {
            throw new IllegalArgumentException("INVALID_TEXT");
        }
        int count = 0;
        for (int offset = 0; offset < text.length();) {
            char character = text.charAt(offset);
            if (Character.isLowSurrogate(character)
                    || (Character.isHighSurrogate(character)
                    && (offset + 1 == text.length() || !Character.isLowSurrogate(text.charAt(offset + 1))))) {
                throw new IllegalArgumentException("INVALID_TEXT");
            }
            int point = text.codePointAt(offset);
            if (Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t') {
                throw new IllegalArgumentException("INVALID_TEXT");
            }
            offset += Character.charCount(point);
            if (++count > maxCodePoints) {
                throw new IllegalArgumentException("INPUT_LIMIT");
            }
        }
        if ((!allowBlank && text.isBlank()) || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("INPUT_LIMIT");
        }
        return text;
    }

    /**
     * 提取来源码点左闭右开区间，不拆代理对。
     * @param text 已校验原文
     * @param start 起点
     * @param end 终点
     * @return 原文切片
     */
    public static String slice(String text, int start, int end) {
        if (start < 0 || end < start || end > text.codePointCount(0, text.length())) {
            throw new IllegalArgumentException("INVALID_RANGE");
        }
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
}
