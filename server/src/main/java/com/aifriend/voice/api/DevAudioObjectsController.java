package com.aifriend.voice.api;

import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.voice.application.DevAudioUploadService;
import com.aifriend.voice.infrastructure.LocalAudioUploadTargetAdapter;

/**
 * dev/test 环境同源私有音频对象上传 Controller。
 *
 * <p>该端点只认证短期上传秘密，不接收或要求 JWT；正式环境不注册。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@Profile({"dev", "test"})
@RequestMapping("/dev/audio-objects")
public class DevAudioObjectsController {

    private final DevAudioUploadService devAudioUploadService;

    /**
     * 创建开发态音频对象上传 Controller。
     *
     * @param devAudioUploadService 开发态音频上传服务
     */
    public DevAudioObjectsController(DevAudioUploadService devAudioUploadService) {
        this.devAudioUploadService = devAudioUploadService;
    }

    /**
     * 使用一次性请求头秘密上传严格匹配凭证元数据的音频字节。
     *
     * @param audioObjectId au_ 前缀音频对象编号
     * @param uploadToken 上传秘密，不得记录
     * @param contentType 实际媒体类型
     * @param audioContent 原始音频字节，不得记录或进入 MySQL
     * @return HTTP 204 空响应
     */
    @PutMapping("/{audioObjectId}")
    public ResponseEntity<Void> uploadAudioObject(
            @PathVariable String audioObjectId,
            @RequestHeader(LocalAudioUploadTargetAdapter.UPLOAD_TOKEN_HEADER)
            @Size(min = 32, max = 128) String uploadToken,
            @RequestHeader("Content-Type") @Size(max = 100) String contentType,
            @RequestBody @Size(min = 1, max = 20_971_520) byte[] audioContent) {
        devAudioUploadService.upload(
                audioObjectId, uploadToken, contentType, audioContent);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .build();
    }
}
