package com.aifriend.voicecollection.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;
import com.aifriend.voicecollection.application.CreateVoiceCollectionSampleCommand;
import com.aifriend.voicecollection.application.VoiceCollectionDeletionView;
import com.aifriend.voicecollection.application.VoiceCollectionSampleView;
import com.aifriend.voicecollection.application.VoiceCollectionService;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingAuthorizationCommand;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingAuthorizationService;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingAuthorizationView;

/**
 * 当前用户封闭测试语音采集 Controller。
 *
 * @author codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/voice-collection-samples")
public class VoiceCollectionSamplesController {

    private final VoiceCollectionService service;

    private final VoiceCollectionTrainingAuthorizationService trainingAuthorizationService;

    /**
     * 创建采集 Controller。
     *
     * @param service 语音采集用例服务
     * @param trainingAuthorizationService 样本训练授权用例服务
     */
    public VoiceCollectionSamplesController(
            VoiceCollectionService service,
            VoiceCollectionTrainingAuthorizationService trainingAuthorizationService) {
        this.service = service;
        this.trainingAuthorizationService = trainingAuthorizationService;
    }

    /**
     * 登记一条已上传且已校验的封闭测试语音样本。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 创建幂等键
     * @param request 采集元数据
     * @return HTTP 201 与样本最小投影
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VoiceCollectionSampleResp> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
            String idempotencyKey,
            @Valid @RequestBody CreateVoiceCollectionSampleReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        VoiceCollectionSampleView view = service.create(
                currentUser.id(),
                idempotencyKey,
                new CreateVoiceCollectionSampleCommand(
                        request.audioObjectId(), request.category(), request.promptCode(),
                        request.environment(), request.dialectCode(),
                        request.consentPolicyVersion(), request.reviewedTranscript(),
                        request.reviewConfirmed(), request.reviewPolicyVersion()));
        return ApiResponse.success(toResponse(view));
    }
    /**
     * 查询当前仍有效的封闭测试语音样本。
     *
     * @param jwt 已验证 JWT
     * @return 最多一百条样本
     */
    @GetMapping
    public ApiResponse<List<VoiceCollectionSampleResp>> list(
            @AuthenticationPrincipal Jwt jwt) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        return ApiResponse.success(service.listActive(currentUser.id()).stream()
                .map(this::toResponse)
                .toList());
    }


    /**
     * 明确授予或撤回单条测试样本的训练资格。
     *
     * @param jwt 已验证 JWT
     * @param sampleId vs_ 前缀样本编号
     * @param idempotencyKey 更新幂等键
     * @param request 决定、明确确认、政策版本和样本版本
     * @return 当前样本训练资格与新版本
     */
    @PutMapping("/{sampleId}/training-authorization")
    public ApiResponse<VoiceCollectionTrainingAuthorizationResp> updateTrainingAuthorization(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "vs_[A-Fa-f0-9]{32}") String sampleId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
            String idempotencyKey,
            @Valid @RequestBody UpdateVoiceCollectionTrainingAuthorizationReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        VoiceCollectionTrainingAuthorizationView view = trainingAuthorizationService.update(
                currentUser.id(),
                sampleId,
                idempotencyKey,
                new VoiceCollectionTrainingAuthorizationCommand(
                        request.decision(), request.confirmed(),
                        request.policyVersion(), request.expectedVersion()));
        return ApiResponse.success(new VoiceCollectionTrainingAuthorizationResp(
                view.sampleId(), view.trainingEligible(), view.version(), view.decidedAt()));
    }

    /**
     * 受理单条样本删除并立即停止展示和使用。
     *
     * @param jwt 已验证 JWT
     * @param sampleId vs_ 前缀样本编号
     * @param idempotencyKey 删除幂等键
     * @param request 明确确认和版本
     * @return HTTP 202 删除受理结果
     */
    @DeleteMapping("/{sampleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VoiceCollectionDeletionResp> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "vs_[A-Fa-f0-9]{32}") String sampleId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
            String idempotencyKey,
            @Valid @RequestBody DeleteVoiceCollectionSampleReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        VoiceCollectionDeletionView view = service.delete(
                currentUser.id(), sampleId, idempotencyKey,
                request.confirmed(), request.expectedVersion());
        return ApiResponse.success(new VoiceCollectionDeletionResp(
                view.id(), view.status(), view.requestedAt()));
    }

    private VoiceCollectionSampleResp toResponse(VoiceCollectionSampleView view) {
        return new VoiceCollectionSampleResp(
                view.id(), view.category(), view.promptCode(), view.environment(),
                view.dialectCode(), view.status(), view.reviewStatus(),
                view.trainingEligible(), view.reviewedAt(), view.retentionUntil(),
                view.version(), view.createdAt());
    }
}
