package com.aifriend.voice.application;

import java.util.Arrays;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.domain.AudioObject;

/**
 * 音频对象字节、魔数、解码和真实时长校验服务。
 *
 * <p>客户端声明只作为预期值；大小、SHA-256、容器魔数和解码时长均以存储内容复验结果为准。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AudioObjectContentValidationService {

    private static final int MIN_DURATION_MS = 200;
    private static final int MAX_DURATION_MS = 60_000;
    private static final int MIN_DURATION_TOLERANCE_MS = 250;

    private final DigestService digestService;
    private final AudioObjectContentInspectorPort contentInspectorPort;

    /**
     * 创建音频内容校验服务。
     *
     * @param digestService SHA-256 与常量时间比较服务
     * @param contentInspectorPort 音频解码与真实时长检查端口
     */
    public AudioObjectContentValidationService(
            DigestService digestService,
            AudioObjectContentInspectorPort contentInspectorPort) {
        this.digestService = digestService;
        this.contentInspectorPort = contentInspectorPort;
    }

    /**
     * 对指定元数据的存储内容执行完整校验。
     *
     * <p>时长误差允许值取 250 毫秒与声明时长 5% 的较大值，
     * 只用于容纳客户端预检和服务端帧边界的舍入差异。
     *
     * @param audioObject 事务外读取的 owner 范围元数据快照
     * @param storedAudioObject 已按指定版本读取的私有对象
     * @return 可用于事务内一次性消费的已校验快照
     * @throws BusinessException 当大小、摘要、魔数、解码或真实时长不合格时抛出
     */
    public ValidatedAudioObject validate(
            AudioObject audioObject,
            StoredAudioObject storedAudioObject) {
        byte[] audioContent = storedAudioObject.audioContent();
        try {
            String storageVersion = storedAudioObject.storageVersion();
            boolean strongStorageVersion = storageVersion != null
                    && !storageVersion.isBlank();
            boolean expectedStorageVersion = audioObject.storageVersion() == null
                    || (storageVersion != null
                            && storageVersion.equals(audioObject.storageVersion()));
            boolean basicMetadataMatches = audioContent.length == audioObject.expectedSizeBytes()
                    && strongStorageVersion
                    && expectedStorageVersion
                    && digestService.constantTimeEquals(
                            audioObject.expectedSha256(), digestService.sha256(audioContent));
            if (!basicMetadataMatches
                    || !hasExpectedMagic(audioObject.mediaType(), audioContent)) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            AudioObjectInspection inspection = contentInspectorPort.inspect(
                    audioObject.mediaType(), audioContent);
            validateDuration(audioObject.expectedDurationMs(), inspection.durationMs());
            return new ValidatedAudioObject(
                    audioObject.id(),
                    audioObject.ownerUserId(),
                    audioObject.purpose(),
                    audioObject.mediaType(),
                    audioContent,
                    inspection.durationMs(),
                    storedAudioObject.storageVersion(),
                    audioObject.version());
        } finally {
            Arrays.fill(audioContent, (byte) 0);
        }
    }

    private void validateDuration(int expectedDurationMs, int actualDurationMs) {
        int toleranceMs = Math.max(
                MIN_DURATION_TOLERANCE_MS,
                Math.round(expectedDurationMs * 0.05F));
        boolean durationMatches = actualDurationMs >= MIN_DURATION_MS
                && actualDurationMs <= MAX_DURATION_MS
                && Math.abs((long) actualDurationMs - expectedDurationMs) <= toleranceMs;
        if (!durationMatches) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }

    private boolean hasExpectedMagic(String mediaType, byte[] content) {
        return switch (mediaType) {
            case "audio/wav" -> startsWith(content, 0, "RIFF")
                    && startsWith(content, 8, "WAVE");
            case "audio/ogg" -> startsWith(content, 0, "OggS");
            case "audio/aac" -> content.length >= 2
                    && (content[0] & 0xFF) == 0xFF
                    && (content[1] & 0xF6) == 0xF0;
            case "audio/mp4" -> content.length >= 12
                    && startsWith(content, 4, "ftyp")
                    && readUnsignedInt(content, 0) >= 8;
            default -> false;
        };
    }

    private boolean startsWith(byte[] content, int offset, String expectedAscii) {
        byte[] expected = expectedAscii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return content.length >= offset + expected.length
                && Arrays.equals(content, offset, offset + expected.length,
                        expected, 0, expected.length);
    }

    private long readUnsignedInt(byte[] content, int offset) {
        return ((long) (content[offset] & 0xFF) << 24)
                | ((long) (content[offset + 1] & 0xFF) << 16)
                | ((long) (content[offset + 2] & 0xFF) << 8)
                | (content[offset + 3] & 0xFFL);
    }
}
