package com.aifriend.voice.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;

/**
 * dev/test 环境同源音频上传服务。
 *
 * <p>只接受服务端签发的自定义请求头秘密，并在数据库写锁内完成本地文件只创建写入与元数据更新。
 * 本服务不在 prod 注册，正式环境应由客户端直传私有对象存储。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
@Profile({"dev", "test"})
public class DevAudioUploadService {

    private static final byte[] DUMMY_DIGEST = new byte[32];

    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final AudioObjectStoragePort audioObjectStoragePort;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建开发态同源音频上传服务。
     *
     * @param audioObjectRepositoryPort 音频对象持久化端口
     * @param audioObjectStoragePort 本地私有对象存储端口
     * @param digestService SHA-256 与常量时间比较服务
     * @param clock UTC 时钟
     */
    public DevAudioUploadService(
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioObjectStoragePort audioObjectStoragePort,
            DigestService digestService,
            Clock clock) {
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.audioObjectStoragePort = audioObjectStoragePort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 校验一次性上传秘密和音频元数据后保存本地开发对象。
     *
     * @param publicAudioObjectId au_ 前缀音频对象编号
     * @param uploadToken 上传秘密，不得记录
     * @param mediaType 实际 Content-Type
     * @param audioContent 原始音频字节，不得记录或写入 MySQL
     * @throws BusinessException 当凭据、期限、状态、大小、类型或哈希不匹配时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void upload(
            String publicAudioObjectId,
            String uploadToken,
            String mediaType,
            byte[] audioContent) {
        if (audioContent == null || audioContent.length == 0
                || audioContent.length > 20_971_520) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        UUID audioObjectId = PublicIdCodec.parseAudioObjectId(publicAudioObjectId);
        AudioObject audioObject = audioObjectRepositoryPort
                .findByIdForUpdate(audioObjectId)
                .orElse(null);
        byte[] presentedTokenDigest = digestService.sha256(uploadToken == null ? "" : uploadToken);
        byte[] expectedTokenDigest = audioObject == null
                ? DUMMY_DIGEST : audioObject.uploadTokenDigest();
        boolean tokenMatches = digestService.constantTimeEquals(
                expectedTokenDigest, presentedTokenDigest);
        if (audioObject == null || !tokenMatches) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        validateUpload(audioObject, mediaType, audioContent, Instant.now(clock));
        String storageVersion = audioObjectStoragePort.store(
                audioObject.objectKey(), audioContent);
        audioObjectRepositoryPort.save(
                audioObject.markUploaded(storageVersion, Instant.now(clock)));
    }

    private void validateUpload(
            AudioObject audioObject,
            String mediaType,
            byte[] audioContent,
            Instant now) {
        String normalizedMediaType = mediaType == null
                ? "" : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean metadataMatches = audioObject.status() == AudioObjectStatus.ISSUED
                && audioObject.uploadedAt() == null
                && audioObject.uploadExpiresAt().isAfter(now)
                && audioObject.mediaType().equals(normalizedMediaType)
                && audioObject.expectedSizeBytes() == audioContent.length
                && digestService.constantTimeEquals(
                        audioObject.expectedSha256(), digestService.sha256(audioContent));
        if (!metadataMatches) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }
}
