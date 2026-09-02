package com.aifriend.invitation.api;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.invitation.application.AcceptedInvitation;
import com.aifriend.invitation.application.InvitationAcceptanceService;
import com.aifriend.invitation.application.InvitationDecisionService;
import com.aifriend.invitation.application.InvitationSessionProperties;
import com.aifriend.shared.api.ApiResponse;

/**
 * 受限邀请会话中的亲友决策 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/public/contact-invitations")
public class PublicContactInvitationsController {

    private static final String COOKIE_NAME = "__Host-ai_friend_invitation";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final InvitationDecisionService invitationDecisionService;
    private final InvitationAcceptanceService invitationAcceptanceService;
    private final InvitationSessionProperties properties;

    /**
     * 创建公开邀请决策 Controller。
     *
     * @param invitationDecisionService 邀请决策服务
     * @param invitationAcceptanceService 邀请接受服务
     * @param properties 受限会话固定配置
     */
    public PublicContactInvitationsController(
            InvitationDecisionService invitationDecisionService,
            InvitationAcceptanceService invitationAcceptanceService,
            InvitationSessionProperties properties) {
        this.invitationDecisionService = invitationDecisionService;
        this.invitationAcceptanceService = invitationAcceptanceService;
        this.properties = properties;
    }

    /**
     * 亲友在看到用途和政策后明确接受当前邀请。
     *
     * @param sessionToken HttpOnly 邀请 Cookie
     * @param csrfToken 当前会话 CSRF token
     * @param idempotencyKey 接受操作幂等键
     * @param request 明确确认和政策版本
     * @return HTTP 200 与等待老人登记称呼状态
     */
    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<InvitationDecisionResp>> acceptPublicContactInvitation(
            @CookieValue(name = COOKIE_NAME, required = false) String sessionToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfToken,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody AcceptInvitationReq request) {
        AcceptedInvitation accepted = invitationAcceptanceService.accept(
                sessionToken, csrfToken, idempotencyKey,
                request.confirmed(), request.consentPolicyVersion(), request.wechatId());
        ResponseCookie clearedCookie = clearedInvitationCookie();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new InvitationDecisionResp(accepted.status())));
    }

    /**
     * 明确拒绝当前邀请并注销受限会话。
     *
     * @param sessionToken HttpOnly 邀请 Cookie，由浏览器自动提交
     * @param csrfToken 同源页面提交的 CSRF token
     * @param idempotencyKey 拒绝操作幂等键
     * @param request 必须明确 confirmed=true 的请求体
     * @return HTTP 204、清除 Cookie 和禁止缓存响应头
     */
    @PostMapping("/decline")
    public ResponseEntity<Void> declinePublicContactInvitation(
            @CookieValue(name = COOKIE_NAME, required = false) String sessionToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfToken,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody DeclineInvitationReq request) {
        invitationDecisionService.decline(
                sessionToken, csrfToken, idempotencyKey, request.confirmed());
        ResponseCookie clearedCookie = clearedInvitationCookie();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private ResponseCookie clearedInvitationCookie() {
        return ResponseCookie.from(properties.cookieName(), "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
