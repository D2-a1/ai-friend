package com.aifriend.core.network

import com.aifriend.contract.model.ErrorResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.Response

/** 只读取稳定错误码，不向日志或异常消息复制服务端响应正文。 */
@Singleton
class ApiErrorCodeReader @Inject constructor(
    private val json: Json,
) {
    fun read(response: Response<*>): String? = runCatching {
        val body = response.errorBody()?.string() ?: return null
        json.decodeFromString<ErrorResponse>(body).code.value
    }.getOrNull()
}
