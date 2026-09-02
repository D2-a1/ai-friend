package com.aifriend.voice.infrastructure;

import java.net.URI;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.application.AudioStorageProperties;
import com.aifriend.voice.application.AudioUploadTarget;
import com.aifriend.voice.application.AudioUploadTargetPort;
import com.aifriend.voice.domain.AudioObject;

/**
 * dev/test 环境同源私有音频上传目标适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class LocalAudioUploadTargetAdapter implements AudioUploadTargetPort {

    /** 上传秘密请求头名称。 */
    public static final String UPLOAD_TOKEN_HEADER = "X-Audio-Upload-Token";

    private final AudioStorageProperties properties;

    /**
     * 创建开发态同源上传目标适配器。
     *
     * @param properties 音频上传配置
     */
    public LocalAudioUploadTargetAdapter(AudioStorageProperties properties) {
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public AudioUploadTarget createTarget(AudioObject audioObject, String uploadToken) {
        String baseUrl = properties.devUploadBaseUrl().toString();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        URI uploadUrl = URI.create(
                normalizedBaseUrl + PublicIdCodec.audioObjectId(audioObject.id()));
        return new AudioUploadTarget(
                uploadUrl,
                "PUT",
                Map.of(
                        UPLOAD_TOKEN_HEADER, uploadToken,
                        "Content-Type", audioObject.mediaType()));
    }
}
