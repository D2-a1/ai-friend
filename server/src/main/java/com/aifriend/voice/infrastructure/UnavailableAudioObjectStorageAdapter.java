package com.aifriend.voice.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.StoredAudioObject;

/**
 * 非开发环境真实私有对象存储尚未接入时的失败关闭适配器。
 *
 * <p>保持应用其他功能可启动，但任何音频存储读写和删除均返回上游不可用，
 * 不得回落到本地目录或伪造内容校验成功。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("!dev & !test")
@ConditionalOnProperty(
        prefix = "ai-friend.audio.oss",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class UnavailableAudioObjectStorageAdapter implements AudioObjectStoragePort {

    /**
     * 创建失败关闭对象存储适配器。
     */
    public UnavailableAudioObjectStorageAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public String store(String objectKey, byte[] audioContent) {
        throw unavailable();
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readCurrent(
            String objectKey,
            long maximumBytes) {
        throw unavailable();
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readExact(
            String objectKey,
            String expectedStorageVersion,
            long maximumBytes) {
        throw unavailable();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String objectKey) {
        throw unavailable();
    }

    private UpstreamFailureException unavailable() {
        return new UpstreamFailureException("音频存储暂不可用，请稍后再试");
    }
}
