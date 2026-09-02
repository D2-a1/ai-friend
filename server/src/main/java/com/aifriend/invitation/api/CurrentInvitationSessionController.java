package com.aifriend.invitation.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.invitation.application.InvitationAcceptanceService;
import com.aifriend.invitation.application.InvitationSessionView;
import com.aifriend.shared.api.ApiResponse;

/**
 * 当前受限邀请会话查询 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@RestController
@RequestMapping("/public/contact-invitation-sessions/current")
public class CurrentInvitationSessionController {

    private static final String COOKIE_NAME = "__Host-ai_friend_invitation";
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final InvitationAcceptanceService invitationAcceptanceService;

    /**
     * 创建当前邀请会话 Controller。
     *
     * @param invitationAcceptanceService 邀请会话查询服务
     */
    public CurrentInvitationSessionController(
            InvitationAcceptanceService invitationAcceptanceService) {
        this.invitationAcceptanceService = invitationAcceptanceService;
    }

    /**
     * 返回已完成微信身份验证的最小邀请信息。
     *
     * @param sessionToken HttpOnly 邀请 Cookie，由浏览器自动提交
     * @return HTTP 200、禁止缓存的当前邀请信息
     */
    @GetMapping
    public ResponseEntity<ApiResponse<InvitationSessionViewResp>>
            getCurrentPublicContactInvitationSession(
                    @CookieValue(name = COOKIE_NAME, required = false) String sessionToken) {
        InvitationSessionView view = invitationAcceptanceService.view(sessionToken);
        InvitationSessionViewResp response = new InvitationSessionViewResp(
                view.inviterDisplayName(), view.relationshipSummary(),
                view.consentPolicyVersion(), view.expiresAt(), view.readyForConsent(),
                view.csrfToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}
