package com.aifriend.feature.audio

import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.CreateAudioUploadTicketRequest

/**
 * 受限音频凭证申请与二进制直传仓库。
 *
 * @author codex
 * @since 2026-08-10
 */
interface AudioUploadRepository {

    /**
     * 申请短期凭证并立即上传当前内存中的音频，成功后只返回音频对象编号。
     */
    suspend fun upload(
        purpose: AudioPurpose,
        mediaType: CreateAudioUploadTicketRequest.MediaType,
        durationMs: Int,
        audioContent: ByteArray,
    ): String
}
