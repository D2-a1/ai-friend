package com.aifriend.retrieval.infrastructure;

import java.time.Duration;

/**
 * 单个固定模型目标的有界HTTP传输；私有图谱不接入此边界。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeModelTransport extends AutoCloseable {
    /**
     * 单次JSON请求，传输层不自动重试。
     * @param payload 已最小化的公开文本请求
     * @param budget 包含响应完整读取的剩余预算
     * @return 有界响应字节
     */
    byte[] post(byte[] payload, Duration budget);

    /** 释放本实例资源，不影响任务模型客户端。 */
    @Override void close();
}
