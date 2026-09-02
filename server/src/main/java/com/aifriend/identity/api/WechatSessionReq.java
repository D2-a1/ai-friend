package com.aifriend.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 微信一次性 code 登录请求。
 *
 * @param code 微信一次性 code
 * @param device Android 设备上下文
 * @author Codex
 * @since 1.0.0
 */
public record WechatSessionReq(
        @NotBlank @Size(max = 512) String code,
        @NotNull @Valid DeviceContextReq device) {
}
