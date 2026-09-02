package com.aifriend.feature.audio

/**
 * 不携带上传秘密、对象键或原始音频的受控上传异常。
 *
 * @author codex
 * @since 2026-08-10
 */
class AudioUploadException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
