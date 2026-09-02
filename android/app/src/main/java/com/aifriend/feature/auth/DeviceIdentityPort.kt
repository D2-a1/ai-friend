package com.aifriend.feature.auth

/**
 * Android 安装实例设备公钥与一次性凭据签名端口。
 *
 * @author codex
 * @since 2026-08-29
 */
interface DeviceIdentityPort {

    /** 返回可填写到服务器白名单的 64 位小写公钥 SHA-256 指纹。 */
    fun fingerprint(): String

    /** 返回 X.509 SPKI 公钥标准 Base64。 */
    fun publicKeySpkiBase64(): String

    /** 对微信一次性登录 code 签名。 */
    fun signLogin(code: String): String

    /** 对一次性轮换刷新令牌签名。 */
    fun signRefresh(refreshToken: String): String
}
