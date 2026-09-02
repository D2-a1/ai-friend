package com.aifriend.voice.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;

/**
 * dev/test 环境过期音频对象清理服务。
 *
 * <p>按有界批次加锁复验，只删除已经超过上传期限且仍为 ISSUED 的临时对象；
 * 本地文件删除成功后才把数据库状态推进到 DELETED。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
@Profile({"dev", "test"})
public class DevAudioObjectCleanupService {

    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final AudioObjectStoragePort audioObjectStoragePort;
    private final AudioStorageProperties properties;
    private final Clock clock;

    /**
     * 创建开发态过期音频清理服务。
     *
     * @param audioObjectRepositoryPort 音频对象持久化端口
     * @param audioObjectStoragePort 本地私有对象存储端口
     * @param properties 音频存储配置
     * @param clock UTC 时钟
     */
    public DevAudioObjectCleanupService(
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioObjectStoragePort audioObjectStoragePort,
            AudioStorageProperties properties,
            Clock clock) {
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.audioObjectStoragePort = audioObjectStoragePort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 清理一批已过上传期限的开发态音频对象。
     *
     * @return 本次成功推进到 DELETED 的对象数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanupExpired() {
        Instant now = Instant.now(clock);
        int deletedCount = 0;
        for (AudioObject candidate : audioObjectRepositoryPort.findExpiredIssued(
                now, properties.cleanupBatchSize())) {
            AudioObject locked = audioObjectRepositoryPort.findByIdForUpdate(candidate.id())
                    .orElse(null);
            if (locked == null
                    || locked.status() != AudioObjectStatus.ISSUED
                    || locked.uploadedAt() != null
                    || locked.uploadExpiresAt().isAfter(now)) {
                continue;
            }
            AudioObject expired = locked.expire(now);
            audioObjectRepositoryPort.save(expired);
            audioObjectStoragePort.delete(expired.objectKey());
            audioObjectRepositoryPort.save(expired.markDeleted(now));
            deletedCount++;
        }
        return deletedCount;
    }
}
