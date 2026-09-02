package com.aifriend.feature.audio

import com.aifriend.contract.api.AudioApi
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.AudioUploadTicket
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.core.network.UnauthenticatedUploadClient
import com.aifriend.feature.auth.AuthSessionRepository
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 使用 OpenAPI 申请受限凭证，并通过独立无 JWT 客户端上传音频。
 *
 * <p>凭证秘密、对象键和音频字节只保留在当前调用内存；上传失败不自动重试，
 * 避免旧确认或旧音频被离线补执行。
 *
 * @author codex
 * @since 2026-08-10
 */
@Singleton
class DefaultAudioUploadRepository @Inject constructor(
    private val audioApi: AudioApi,
    private val authSessionRepository: AuthSessionRepository,
    @param:UnauthenticatedUploadClient private val uploadClient: OkHttpClient,
) : AudioUploadRepository {

    override suspend fun upload(
        purpose: AudioPurpose,
        mediaType: CreateAudioUploadTicketRequest.MediaType,
        durationMs: Int,
        audioContent: ByteArray,
    ): String {
        validateAudio(durationMs, audioContent)
        val idempotencyKey = UUID.randomUUID().toString()
        val ticketRequest = CreateAudioUploadTicketRequest(
            purpose = purpose,
            mediaType = mediaType,
            sizeBytes = audioContent.size.toLong(),
            durationMs = durationMs,
            sha256 = audioContent.sha256Hex(),
        )
        var response = audioApi.createAudioUploadTicket(idempotencyKey, ticketRequest)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = audioApi.createAudioUploadTicket(idempotencyKey, ticketRequest)
        }
        val ticket = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AudioUploadException(
                response.code(),
                ticketFailureMessage(response.code()),
            )
        uploadOnce(ticket, mediaType.value, audioContent)
        return ticket.audioObjectId
    }

    private suspend fun uploadOnce(
        ticket: AudioUploadTicket,
        mediaType: String,
        audioContent: ByteArray,
    ) = withContext(Dispatchers.IO) {
        validateUploadTarget(ticket)
        val requestBuilder = Request.Builder().url(ticket.uploadUrl.toASCIIString())
        ticket.requiredHeaders.forEach { (name, value) ->
            if (name.lowercase() in FORBIDDEN_UPLOAD_HEADERS) {
                throw AudioUploadException(null, "音频上传凭证无效，请重新录制")
            }
            requestBuilder.header(name, value)
        }
        val body = audioContent.toRequestBody(mediaType.toMediaType())
        requestBuilder.method(ticket.method.value, body)
        try {
            uploadClient.newCall(requestBuilder.build()).execute().use { uploadResponse ->
                if (!uploadResponse.isSuccessful) {
                    throw AudioUploadException(
                        uploadResponse.code,
                        "音频上传失败，请重新录制",
                    )
                }
            }
        } catch (exception: AudioUploadException) {
            throw exception
        } catch (exception: IOException) {
            throw AudioUploadException(
                null,
                uploadNetworkFailureMessage(
                    exception,
                    ticket.uploadUrl.host.orEmpty(),
                ),
                exception,
            )
        }
    }

    private fun validateUploadTarget(ticket: AudioUploadTicket) {
        val uri = ticket.uploadUrl
        if (uri.scheme !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null ||
            ticket.method !in setOf(AudioUploadTicket.Method.PUT, AudioUploadTicket.Method.POST)
        ) {
            throw AudioUploadException(null, "音频上传凭证无效，请重新录制")
        }
    }

    private fun validateAudio(durationMs: Int, audioContent: ByteArray) {
        if (durationMs !in 200..60_000 || audioContent.isEmpty() ||
            audioContent.size > MAX_AUDIO_BYTES
        ) {
            throw AudioUploadException(null, "音频无效，请重新录制")
        }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun ticketFailureMessage(status: Int): String = when (status) {
        403 -> "请先完成当前语音用途授权"
        409 -> "录音状态已经变化，请重新录制"
        429 -> "操作太频繁，请稍后再试"
        502 -> "音频存储暂不可用，请稍后再试"
        else -> "无法创建音频上传凭证，请稍后再试"
    }

    /**
     * 将底层网络异常收敛为不包含地址、对象键或凭证的中文提示。
     */
    internal fun uploadNetworkFailureMessage(
        exception: IOException,
        uploadHost: String,
    ): String = when (exception) {
        is UnknownHostException -> "无法解析音频存储地址，音频没有上传"
        is SocketTimeoutException -> "连接音频存储超时，音频没有上传"
        is SSLException -> "音频存储安全连接失败，音频没有上传"
        is ConnectException -> when {
            uploadHost in LOCAL_DEVELOPMENT_HOSTS ->
                "服务器返回了本地开发上传地址，音频没有上传"
            uploadHost.endsWith(ALIYUN_OSS_HOST_SUFFIX, ignoreCase = true) ->
                "无法连接阿里云音频存储公网地址，音频没有上传"
            else -> "无法连接音频存储服务，音频没有上传"
        }
        is SocketException -> "音频存储连接中断，音频没有上传"
        else -> "网络异常，音频没有上传"
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 20_971_520
        val FORBIDDEN_UPLOAD_HEADERS = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
            "host",
        )
        const val ALIYUN_OSS_HOST_SUFFIX = ".aliyuncs.com"
        val LOCAL_DEVELOPMENT_HOSTS =
            setOf("10.0.2.2", "127.0.0.1", "localhost")
    }
}
