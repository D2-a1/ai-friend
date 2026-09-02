package com.aifriend.invitation.api;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.invitation.application.InvitationOAuthService;
import com.aifriend.invitation.application.InvitationSessionProperties;

/**
 * 微信内浏览器邀请 OAuth 固定回调 Controller。
 *
 * <p>无论成功或失败都跳转到无敏感查询参数的固定页面，避免 code/state 留在地址栏、
 * Referer 或错误正文中。本 Controller 不自动接受邀请。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/oauth/wechat/invitation-callback")
public class InvitationOAuthController {

    private static final String COOKIE_NAME = "__Host-ai_friend_invitation";
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final InvitationOAuthService invitationOAuthService;
    private final InvitationSessionProperties properties;

    /**
     * 创建邀请 OAuth 回调 Controller。
     *
     * @param invitationOAuthService OAuth 回调服务
     * @param properties 固定回跳地址配置
     */
    public InvitationOAuthController(
            InvitationOAuthService invitationOAuthService,
            InvitationSessionProperties properties) {
        this.invitationOAuthService = invitationOAuthService;
        this.properties = properties;
    }

    /**
     * 消费一次性微信 code/state 并跳转到无敏感参数页面。
     *
     * @param sessionToken HttpOnly 邀请 Cookie
     * @param code 微信一次性 code，不得记录
     * @param state 一次性 OAuth state，不得记录
     * @return HTTP 303 与固定同源 Location
     */
    @GetMapping
    public ResponseEntity<Void> completeWechatInvitationOAuth(
            @CookieValue(name = COOKIE_NAME, required = false) String sessionToken,
            @RequestParam @NotBlank @Size(max = 512) String code,
            @RequestParam @NotBlank @Size(min = 32, max = 256) String state) {
        URI target;
        try {
            invitationOAuthService.complete(sessionToken, state, code);
            target = properties.continuationUrl();
        } catch (RuntimeException exception) {
            target = properties.unavailableUrl();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(target)
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
