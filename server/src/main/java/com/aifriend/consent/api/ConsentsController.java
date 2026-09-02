package com.aifriend.consent.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.consent.application.ConsentService;
import com.aifriend.consent.domain.ConsentRecord;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 当前用户分项授权 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/privacy/consents")
public class ConsentsController {

    private final ConsentService consentService;

    /**
     * 创建授权 Controller。
     *
     * @param consentService 授权用例服务
     */
    public ConsentsController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 查询当前用户各授权类型的最新决定。
     *
     * @param jwt 已验证 JWT
     * @return 最新授权记录列表
     */
    @GetMapping
    public ApiResponse<List<ConsentResp>> listMyConsents(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        List<ConsentResp> responses = consentService.listCurrent(currentUser.id()).stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 同意或撤回一类授权。
     *
     * @param jwt 已验证 JWT
     * @param type 授权类型
     * @param idempotencyKey 幂等键
     * @param request 授权决定请求
     * @return 新增或原幂等授权记录
     */
    @PutMapping("/{type}")
    public ApiResponse<ConsentResp> updateMyConsent(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable ConsentType type,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody UpdateConsentReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ConsentRecord record = consentService.update(
                currentUser.id(),
                type,
                request.decision(),
                request.policyVersion(),
                request.confirmedAt(),
                idempotencyKey);
        return ApiResponse.success(toResponse(record));
    }

    private ConsentResp toResponse(ConsentRecord record) {
        return new ConsentResp(
                record.type(),
                record.decision(),
                record.policyVersion(),
                record.decidedAt());
    }
}
