package com.aifriend.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Android 登录设备上下文请求。
 *
 * @param platform 固定 ANDROID
 * @param osVersion Android 版本
 * @param appVersion App 版本
 * @param deviceModel 设备型号，可空
 * @param publicKeySpkiBase64 Android Keystore X.509 SPKI 公钥 Base64
 * @param proofBase64 绑定当前一次性登录 code 的 SHA256withECDSA 签名
 * @author Codex
 * @since 1.0.0
 */
public record DeviceContextReq(
        @NotBlank @Pattern(regexp = "ANDROID") String platform,
        @NotBlank @Size(max = 40) String osVersion,
        @NotBlank @Size(max = 40) String appVersion,
        @Size(max = 80) String deviceModel,
        @Size(max = 512) String publicKeySpkiBase64,
        @Size(max = 256) String proofBase64) {
}
