package com.aifriend.feature.auth

/**
 * 不包含响应正文、token 或微信 code 的受控身份接口异常。
 *
 * @author codex
 * @since 2026-08-04
 */
class AuthApiException(
    val httpStatus: Int,
    message: String,
) : RuntimeException(message)

/** 服务端已可靠受理当前微信身份旧账号注销。 */
class AccountClosureAcceptedException : RuntimeException("注销申请已经受理")
