package com.aifriend.voice.infrastructure;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.voice.application.DevAudioObjectCleanupService;

/**
 * dev/test 环境过期未上传音频对象定时清理入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class DevAudioObjectCleanupScheduler {

    private final DevAudioObjectCleanupService cleanupService;

    /**
     * 创建开发态音频清理调度器。
     *
     * @param cleanupService 有界清理服务
     */
    public DevAudioObjectCleanupScheduler(DevAudioObjectCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /**
     * 按配置间隔清理一批过期且从未上传的对象。
     */
    @Scheduled(fixedDelayString = "${ai-friend.audio.cleanup-interval:5m}")
    public void cleanupExpired() {
        cleanupService.cleanupExpired();
    }
}
