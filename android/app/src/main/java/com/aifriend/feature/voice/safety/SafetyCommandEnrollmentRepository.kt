package com.aifriend.feature.voice.safety

import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary

/**
 * 四类安全指令注册仓库。
 *
 * @author codex
 * @since 2026-08-13
 */
interface SafetyCommandEnrollmentRepository {

    /**
     * 使用八个已上传音频对象原子注册四类安全指令。
     *
     * @param commands 固定四类指令及其双录音频对象
     * @param consentPolicyVersion 用户明确同意的模板政策版本
     */
    suspend fun enroll(
        commands: List<SafetyCommandAudioObjects>,
        consentPolicyVersion: String,
    ): List<VoiceTemplateSummary>
}

/**
 * 单类安全指令的两个音频对象。
 */
data class SafetyCommandAudioObjects(
    val type: SafetyCommandType,
    val firstAudioObjectId: String,
    val secondAudioObjectId: String,
)
