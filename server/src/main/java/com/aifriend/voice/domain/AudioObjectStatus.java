package com.aifriend.voice.domain;

/**
 * 临时音频对象业务生命周期状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AudioObjectStatus {
    /** 凭证已签发，等待上传或业务接口一次性消费。 */
    ISSUED,
    /** 已被任务、称呼或安全指令业务接口一次性消费。 */
    CONSUMED,
    /** 上传凭证已过期，不允许继续上传或消费。 */
    EXPIRED,
    /** 对象存储内容已清理。 */
    DELETED
}
