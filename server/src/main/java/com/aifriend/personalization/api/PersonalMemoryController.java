package com.aifriend.personalization.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.personalization.application.PersonalMemoryService;
import com.aifriend.personalization.application.PersonalMemorySnapshot;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 当前用户长期个人偏好的管理接口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/me/personal-memory")
public class PersonalMemoryController {

    private final PersonalMemoryService service;

    /**
     * 创建管理接口。
     *
     * @param service 长期偏好服务
     */
    public PersonalMemoryController(PersonalMemoryService service) {
        this.service = service;
    }

    /**
     * 查看能力开关、授权和当前偏好。
     *
     * @param jwt 已验证 JWT
     * @return 当前管理状态
     */
    @GetMapping
    public ApiResponse<PersonalMemoryResp> get(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(toResponse(
                service.get(CurrentUser.from(jwt).id())));
    }

    /**
     * 创建或更正三个有限偏好。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 幂等键
     * @param request 偏好和当前版本
     * @return 更新后的管理状态
     */
    @PutMapping
    public ApiResponse<PersonalMemoryResp> update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody UpdatePersonalMemoryReq request) {
        PersonalMemoryPreferences preferences = new PersonalMemoryPreferences(
                request.speechRate(), request.dialogueStyle(), request.ambiguousCall());
        return ApiResponse.success(toResponse(service.update(
                CurrentUser.from(jwt).id(), idempotencyKey,
                preferences, request.expectedVersion())));
    }

    /**
     * 明确删除偏好内容；功能开关关闭或授权已撤回也允许调用。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 幂等键
     * @param request 明确确认和当前版本
     * @return 删除后的管理状态
     */
    @DeleteMapping
    public ApiResponse<PersonalMemoryResp> delete(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody DeletePersonalMemoryReq request) {
        return ApiResponse.success(toResponse(service.delete(
                CurrentUser.from(jwt).id(), idempotencyKey,
                request.confirmed(), request.expectedVersion())));
    }

    private PersonalMemoryResp toResponse(PersonalMemorySnapshot snapshot) {
        PersonalMemoryPreferences preferences = snapshot.preferences();
        PersonalMemoryPreferencesResp response = preferences == null ? null
                : new PersonalMemoryPreferencesResp(
                        preferences.speechRate(), preferences.dialogueStyle(),
                        preferences.ambiguousCall());
        return new PersonalMemoryResp(
                snapshot.featureEnabled(), snapshot.consentGranted(),
                snapshot.policyVersion(), response, snapshot.version(),
                snapshot.updatedAt());
    }
}
