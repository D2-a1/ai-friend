package com.aifriend.core.network

import javax.inject.Qualifier

/**
 * 标记不附加登录令牌、不跟随重定向且不自动重试的二进制上传客户端。
 *
 * @author codex
 * @since 2026-08-10
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedUploadClient
