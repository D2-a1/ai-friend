package com.aifriend.task.application;

import java.util.List;

/**
 * Android 本机基础识别产生的临时证据。
 *
 * <p>正文只参与当前请求处理和加密任务载荷，不得记录日志；客户端识别结果
 * 不等于联系人身份或动作授权，后续仍必须经过声学称呼匹配、复述和明确确认。
 *
 * @param transcript 本机临时转写
 * @param confidence 保守置信度
 * @param modelVersion 固定普通话参考模型版本
 * @param modelArchiveSha256 随 APK 发布模型归档摘要
 * @param words 词级时间戳
 * @author Codex
 * @since 1.0.0
 */
public record TaskClientRecognitionEvidence(
        String transcript,
        double confidence,
        String modelVersion,
        String modelArchiveSha256,
        List<TaskRecognizedWord> words) {

    /** 固化词列表。 */
    public TaskClientRecognitionEvidence {
        words = words == null ? List.of() : List.copyOf(words);
    }

    /**
     * 生成请求摘要输入；调用方只对结果做 SHA-256，不得记录明文。
     *
     * @return 稳定证据串
     */
    public String fingerprintInput() {
        StringBuilder value = new StringBuilder()
                .append(transcript).append('|').append(confidence).append('|')
                .append(modelVersion).append('|').append(modelArchiveSha256);
        words.forEach(word -> value.append('|').append(word.text())
                .append(':').append(word.startMs()).append(':').append(word.endMs())
                .append(':').append(word.confidence()));
        return value.toString();
    }
}
