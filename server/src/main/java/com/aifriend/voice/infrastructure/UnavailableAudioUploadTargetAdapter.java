package com.aifriend.voice.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.voice.application.AudioUploadTarget;
import com.aifriend.voice.application.AudioUploadTargetPort;
import com.aifriend.voice.domain.AudioObject;

/**
 * 非开发环境对象存储尚未接入时的失败关闭适配器。
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
public class UnavailableAudioUploadTargetAdapter implements AudioUploadTargetPort {

    /**
     * 创建失败关闭适配器。
     */
    public UnavailableAudioUploadTargetAdapter() {
    }

    /**
     * 拒绝签发无法安全落到私有对象存储的上传目标。
     *
     * @param audioObject 音频对象快照
     * @param uploadToken 短期上传秘密
     * @return 永不返回
     * @throws UpstreamFailureException 始终抛出，且不持久化音频对象
     */
    @Override
    public AudioUploadTarget createTarget(AudioObject audioObject, String uploadToken) {
        throw new UpstreamFailureException("音频存储暂不可用，请稍后再试");
    }
}
