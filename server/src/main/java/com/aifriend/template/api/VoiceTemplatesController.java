package com.aifriend.template.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;
import com.aifriend.template.application.EnrollSafetyCommandsCommand;
import com.aifriend.template.application.RoutineCommandDeletionCommand;
import com.aifriend.template.application.RoutineCommandDeletionService;
import com.aifriend.template.application.RoutineCommandDeletionView;
import com.aifriend.template.application.SafetyCommandEnrollmentItem;
import com.aifriend.template.application.SafetyCommandEnrollmentResult;
import com.aifriend.template.application.SafetyCommandEnrollmentService;
import com.aifriend.template.application.VoiceTemplateQueryService;
import com.aifriend.template.application.VoiceTemplateSummary;

/**
 * 当前 owner 语音模板清单与安全指令整批注册 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/me")
public class VoiceTemplatesController {

    private final VoiceTemplateQueryService queryService;
    private final SafetyCommandEnrollmentService enrollmentService;
    private final RoutineCommandDeletionService routineDeletionService;

    /**
     * 创建当前 owner 语音模板 Controller。
     *
     * @param queryService 语音模板清单服务
     * @param enrollmentService 安全指令整批注册服务
     * @param routineDeletionService 日常指令模板清除服务
     */
    public VoiceTemplatesController(
            VoiceTemplateQueryService queryService,
            SafetyCommandEnrollmentService enrollmentService,
            RoutineCommandDeletionService routineDeletionService) {
        this.queryService = queryService;
        this.enrollmentService = enrollmentService;
        this.routineDeletionService = routineDeletionService;
    }

    /**
     * 查询当前已认证 owner 的全部已实现语音模板元数据。
     *
     * @param jwt 已验证 JWT
     * @return 不含模板、音频和内部 UUID 的清单
     */
    @GetMapping("/voice-templates")
    public ApiResponse<List<VoiceTemplateResp>> listMyVoiceTemplates(
            @AuthenticationPrincipal Jwt jwt) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        return ApiResponse.success(queryService.list(currentUser.id()).stream()
                .map(this::toResponse)
                .toList());
    }

    /**
     * 用八段已校验录音整批注册四类个人方言安全指令。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 整批注册幂等键
     * @param request 四类双录音和政策版本
     * @return 四类有效模板的最小元数据
     */
    @PostMapping("/safety-command-enrollments")
    public ApiResponse<SafetyCommandEnrollmentResp> enrollSafetyCommands(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody SafetyCommandEnrollmentReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        SafetyCommandEnrollmentResult result = enrollmentService.enroll(
                currentUser.id(), idempotencyKey,
                new EnrollSafetyCommandsCommand(
                        request.commands().stream()
                                .map(item -> new SafetyCommandEnrollmentItem(
                                        item.type(), item.firstAudioObjectId(),
                                        item.secondAudioObjectId()))
                                .toList(),
                        request.consentPolicyVersion()));
        return ApiResponse.success(new SafetyCommandEnrollmentResp(
                result.complete(), result.templates().stream()
                        .map(this::toResponse)
                        .toList()));
    }

    /**
     * 清除当前 owner 的全部日常指令模板。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 删除幂等键
     * @param request 明确确认和可选预期版本
     * @return 删除数量与服务端完成时间
     */
    @DeleteMapping("/routine-command-templates")
    public ApiResponse<RoutineTemplateDeletionResp> deleteMyRoutineCommandTemplates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody RoutineTemplateDeletionReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        RoutineCommandDeletionView result = routineDeletionService.deleteAll(
                currentUser.id(), idempotencyKey,
                new RoutineCommandDeletionCommand(
                        Boolean.TRUE.equals(request.confirmed()), request.expectedVersion()));
        return ApiResponse.success(new RoutineTemplateDeletionResp(
                result.deletedCount(), result.deletedAt()));
    }

    private VoiceTemplateResp toResponse(VoiceTemplateSummary summary) {
        return new VoiceTemplateResp(
                summary.templateId(), summary.category(), summary.contactId(),
                summary.aliasId(), summary.safetyCommandType(),
                summary.routineCommandIntent(), summary.dialectCode(),
                summary.dialectPackageVersion(), summary.modelVersion(),
                summary.thresholdVersion(), summary.compatibility(), summary.updatedAt());
    }
}
