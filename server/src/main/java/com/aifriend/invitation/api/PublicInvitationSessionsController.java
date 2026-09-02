package com.aifriend.invitation.api;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.invitation.application.CreatedInvitationSession;
import com.aifriend.invitation.application.InvitationSessionProperties;
import com.aifriend.invitation.application.InvitationSessionService;
import com.aifriend.shared.api.ApiResponse;

/**
 * 无需 App 登录的公开邀请 proof 兑换 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@RestController
@RequestMapping("/public/contact-invitation-sessions")
public class PublicInvitationSessionsController {

    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final InvitationSessionService invitationSessionService;
    private final InvitationSessionProperties properties;

    /**
     * 创建公开邀请会话 Controller。
     *
     * @param invitationSessionService proof 兑换服务
     * @param properties 会话 Cookie 与期限配置
     */
    public PublicInvitationSessionsController(
            InvitationSessionService invitationSessionService,
            InvitationSessionProperties properties) {
        this.invitationSessionService = invitationSessionService;
        this.properties = properties;
    }

    /**
     * 使用公开编号与 fragment proof 创建固定 30 分钟受限会话。
     *
     * @param request proof 兑换请求，禁止记录请求对象
     * @return HTTP 201、受限 Cookie、CSRF token 与 OAuth 入口
     */
    @PostMapping
    public ResponseEntity<ApiResponse<InvitationSessionCreatedResp>> createPublicContactInvitationSession(
            @Valid @RequestBody CreateInvitationSessionReq request) {
        CreatedInvitationSession session = invitationSessionService.redeem(
                request.invitationId(), request.proof());
        ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), session.sessionToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.ttl())
                .build();
        InvitationSessionCreatedResp response = new InvitationSessionCreatedResp(
                session.expiresAt(), session.csrfToken(), session.wechatAuthorizationUrl());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}
