package com.aifriend.voice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;
import com.aifriend.voice.application.AudioUploadTicketService;
import com.aifriend.voice.application.CreateAudioUploadTicketCommand;
import com.aifriend.voice.application.CreatedAudioUploadTicket;

/**
 * 当前已认证用户受限音频上传凭证 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/audio-upload-tickets")
public class AudioUploadTicketsController {

    private final AudioUploadTicketService audioUploadTicketService;

    /**
     * 创建音频上传凭证 Controller。
     *
     * @param audioUploadTicketService 音频上传凭证用例服务
     */
    public AudioUploadTicketsController(AudioUploadTicketService audioUploadTicketService) {
        this.audioUploadTicketService = audioUploadTicketService;
    }

    /**
     * 为当前已认证 owner 创建固定十分钟的受限音频上传凭证。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 创建幂等键，不得记录
     * @param request 受限用途与预期音频元数据
     * @return HTTP 201 与仅供当前调用内存使用的上传凭证
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AudioUploadTicketResp>> createAudioUploadTicket(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
            String idempotencyKey,
            @Valid @RequestBody CreateAudioUploadTicketReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        CreatedAudioUploadTicket created = audioUploadTicketService.create(
                currentUser.id(),
                idempotencyKey,
                new CreateAudioUploadTicketCommand(
                        request.purpose(),
                        request.mediaType(),
                        request.sizeBytes(),
                        request.durationMs(),
                        request.sha256()));
        AudioUploadTicketResp response = new AudioUploadTicketResp(
                created.audioObjectId(),
                created.uploadTarget().uploadUrl(),
                created.uploadTarget().method(),
                created.uploadTarget().requiredHeaders(),
                created.expiresAt(),
                created.objectKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.success(response));
    }
}
