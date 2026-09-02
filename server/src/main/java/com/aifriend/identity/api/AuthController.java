package com.aifriend.identity.api;

import jakarta.validation.Valid;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.identity.application.LoginService;
import com.aifriend.identity.application.TokenPairResult;
import com.aifriend.identity.application.TokenService;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 微信登录与刷新令牌轮换 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final LoginService loginService;
    private final TokenService tokenService;

    /**
     * 创建身份 Controller。
     *
     * @param loginService 微信登录服务
     * @param tokenService 令牌服务
     */
    public AuthController(LoginService loginService, TokenService tokenService) {
        this.loginService = loginService;
        this.tokenService = tokenService;
    }

    /**
     * 消费微信一次性 code 并创建登录会话。
     *
     * @param request 登录请求
     * @return OpenAPI 令牌对响应
     */
    @PostMapping("/wechat/sessions")
    public ApiResponse<TokenPairResp> createWechatSession(@Valid @RequestBody WechatSessionReq request) {
        return ApiResponse.success(toResponse(loginService.login(
                request.code(),
                request.device().publicKeySpkiBase64(),
                request.device().proofBase64())));
    }

    /**
     * 原子轮换刷新令牌。
     *
     * @param request 刷新令牌请求
     * @return 新令牌对
     */
    @PostMapping("/tokens/refresh")
    public ApiResponse<TokenPairResp> refreshAccessToken(@Valid @RequestBody RefreshTokenReq request) {
        return ApiResponse.success(toResponse(tokenService.rotate(
                request.refreshToken(),
                request.publicKeySpkiBase64(),
                request.proofBase64())));
    }

    private TokenPairResp toResponse(TokenPairResult tokenPair) {
        CurrentUserResp user = new CurrentUserResp(
                PublicIdCodec.userId(tokenPair.user().id()),
                tokenPair.user().status().name(),
                null);
        return new TokenPairResp(
                tokenPair.accessToken(),
                tokenPair.accessTokenExpiresAt(),
                tokenPair.refreshToken(),
                tokenPair.refreshTokenExpiresAt(),
                user);
    }
}
