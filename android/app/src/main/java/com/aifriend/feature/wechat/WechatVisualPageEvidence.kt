package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.aifriend.contract.model.WechatActionType
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** OCR 只保留当前帧中一行文字及其可见范围；不得记录或持久化。 */
data class WechatVisualTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    override fun toString(): String = "WechatVisualTextLine(text=<redacted>, bounds=<redacted>)"
}

data class WechatVisualContactProfileObservation(
    val locatorCandidates: List<CharArray>,
    val callEntryMatchCount: Int,
) {
    fun clear() = locatorCandidates.forEach { it.fill('\u0000') }

    override fun toString(): String =
        "WechatVisualContactProfileObservation(locatorCandidates=<redacted>, " +
            "callEntryMatchCount=$callEntryMatchCount)"
}

data class WechatVisualCallChoiceObservation(
    val voiceMatchCount: Int,
    val videoMatchCount: Int,
)

/** 只有节点证据完全缺失时才允许视觉回退；部分证据必须由核心按冲突失败关闭。 */
object WechatVisualFallbackPolicy {
    fun allows(evidence: WechatSemanticContactProfileEvidence?): Boolean =
        evidence == null ||
            (evidence.locatorCandidates.isEmpty() && evidence.callEntryCandidates.isEmpty())

    fun allows(evidence: WechatSemanticCallChoiceEvidence?): Boolean =
        evidence == null || evidence.actionCandidates.isEmpty()
}

/**
 * 微信自绘页面的窄视觉规则。
 *
 * 只接受严格微信号格式和三个固定动作词；不读取联系人名称、聊天内容或其他页面语义。
 */
object WechatVisualTextEvidenceRule {
    fun contactProfile(lines: List<WechatVisualTextLine>): WechatVisualContactProfileObservation {
        val locators = ArrayList<CharArray>()
        val labels = lines.filter { line ->
            WechatLocalVerificationTextRule.isLocatorLabel(line.text)
        }
        lines.forEach { line ->
            parseInlineLocator(line.text)?.let(locators::add)
        }
        labels.forEach { label ->
            lines.asSequence()
                .filter { candidate -> candidate !== label && sameVisibleRow(label, candidate) }
                .mapNotNull { candidate ->
                    WechatLocalVerificationTextRule.standaloneLocator(candidate.text)
                }
                .forEach(locators::add)
        }
        val callEntries = fixedActionMatchCount(lines, CONTACT_PROFILE_CALL_ENTRY)
        return WechatVisualContactProfileObservation(locators, callEntries)
    }

    fun callChoice(lines: List<WechatVisualTextLine>): WechatVisualCallChoiceObservation =
        WechatVisualCallChoiceObservation(
            voiceMatchCount = fixedActionMatchCount(lines, VOICE_CALL_ENTRY),
            videoMatchCount = fixedActionMatchCount(lines, VIDEO_CALL_ENTRY),
        )

    /**
     * ML Kit may insert spacing inside a Chinese label or split one visible row into two lines.
     * Only the exact fixed phrase after removing spacing is accepted; all other characters fail.
     */
    private fun fixedActionMatchCount(
        lines: List<WechatVisualTextLine>,
        expected: String,
    ): Int {
        val direct = lines.count { line -> line.text.matchesFixedActionLabel(expected) }
        val joined = lines.indices.sumOf { leftIndex ->
            lines.indices.count { rightIndex ->
                if (leftIndex == rightIndex) return@count false
                val left = lines[leftIndex]
                val right = lines[rightIndex]
                if (!sameVisibleRow(left, right) || !isStrictLeftToRightNeighbour(left, right)) {
                    return@count false
                }
                val leftText = left.text.normalizedFixedLabel() ?: return@count false
                val rightText = right.text.normalizedFixedLabel() ?: return@count false
                leftText.isNotEmpty() && rightText.isNotEmpty() && leftText + rightText == expected
            }
        }
        return direct + joined
    }

    private fun isStrictLeftToRightNeighbour(
        left: WechatVisualTextLine,
        right: WechatVisualTextLine,
    ): Boolean {
        val maximumHeight = maxOf(left.bottom - left.top, right.bottom - right.top)
        return maximumHeight > 0 && right.left >= left.right - maximumHeight / 3 &&
            right.left - left.right <= maximumHeight * 3
    }

    private fun String.normalizedFixedLabel(): String? {
        if (length > MAXIMUM_ACTION_TEXT_LENGTH) return null
        return buildString(length) {
            this@normalizedFixedLabel.forEach { character ->
                if (!character.isWechatSpacing()) append(character)
            }
        }
    }

    private fun String.matchesFixedActionLabel(expected: String): Boolean {
        val compact = normalizedFixedLabel() ?: return false
        if (compact == expected) return true
        val start = compact.indexOf(expected)
        if (start < 0 || compact.indexOf(expected, start + 1) >= 0) return false
        val adornments = compact.removeRange(start, start + expected.length)
        return adornments.isNotEmpty() && adornments.length <= MAXIMUM_UI_ADORNMENTS &&
            adornments.none { character ->
                Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
            }
    }

    private fun Char.isWechatSpacing(): Boolean =
        isWhitespace() || Character.isSpaceChar(this) ||
            Character.getType(this) == Character.FORMAT.toInt()

    private fun parseInlineLocator(text: String): CharArray? {
        WechatLocalVerificationTextRule.locator(text)?.let { return it }
        if (text.length > MAXIMUM_TEXT_LENGTH) return null
        val normalized = text.trimWechatSpacing()
        if (!normalized.startsWith(LOCATOR_LABEL)) return null
        var remainder = normalized.substring(LOCATOR_LABEL.length).trimWechatSpacing()
        if (remainder.startsWith(':') || remainder.startsWith('：')) {
            remainder = remainder.substring(1).trimWechatSpacing()
        }
        if (remainder.isEmpty()) return null
        return WechatLocalVerificationTextRule.standaloneLocator(remainder)
    }

    private fun sameVisibleRow(
        label: WechatVisualTextLine,
        candidate: WechatVisualTextLine,
    ): Boolean {
        val overlap = minOf(label.bottom, candidate.bottom) -
            maxOf(label.top, candidate.top)
        val labelWidth = label.right - label.left
        val minimumHeight = minOf(label.bottom - label.top, candidate.bottom - candidate.top)
        return minimumHeight > 0 && overlap * 2 >= minimumHeight &&
            candidate.left >= label.left &&
            candidate.left <= label.right + labelWidth * 5
    }

    private fun String.trimWechatSpacing(): String = trim { character ->
        character.isWhitespace() || Character.isSpaceChar(character) ||
            Character.getType(character) == Character.FORMAT.toInt()
    }

    private const val LOCATOR_LABEL = "微信号"
    private const val CONTACT_PROFILE_CALL_ENTRY = "音视频通话"
    private const val VOICE_CALL_ENTRY = "语音通话"
    private const val VIDEO_CALL_ENTRY = "视频通话"
    private const val MAXIMUM_TEXT_LENGTH = 96
    private const val MAXIMUM_ACTION_TEXT_LENGTH = 24
    private const val MAXIMUM_UI_ADORNMENTS = 2
}

/** 生产调用面只返回已经脱敏的现屏证据。 */
interface WechatVisualPageEvidenceReader {
    suspend fun verifyMessageSearchResult(value: CharArray, point: WechatCalibrationPixelPoint): Boolean = false

    suspend fun verifyMessageSendButton(point: WechatCalibrationPixelPoint): Boolean = false

    suspend fun readContactProfileEvidence(
        locatorSalt: String,
    ): WechatSemanticContactProfileEvidence?

    suspend fun readCallChoiceEvidence(): WechatSemanticCallChoiceEvidence?
}

/**
 * Android 30+ 的内存截图与端侧中文 OCR 实现。
 *
 * 截图不落盘、不上传；OCR 原文不写日志，只在当前方法中按固定词和签名定位摘要使用。
 */
class AndroidWechatVisualPageEvidenceReader(
    private val service: AccessibilityService,
) : WechatVisualPageEvidenceReader {
    override suspend fun verifyMessageSearchResult(value: CharArray, point: WechatCalibrationPixelPoint): Boolean {
        val lines = captureWechatLines() ?: return false
        return WechatShareVisualEvidenceRule.searchResult(lines, value, point)
    }

    override suspend fun verifyMessageSendButton(point: WechatCalibrationPixelPoint): Boolean {
        val lines = captureWechatLines() ?: return false
        return WechatShareVisualEvidenceRule.sendButton(lines, point)
    }
    override suspend fun readContactProfileEvidence(
        locatorSalt: String,
    ): WechatSemanticContactProfileEvidence? {
        val lines = captureWechatLines() ?: return null
        val observation = WechatVisualTextEvidenceRule.contactProfile(lines)
        return try {
            if (observation.locatorCandidates.size > MAXIMUM_CANDIDATES ||
                observation.callEntryMatchCount !in 0..MAXIMUM_CANDIDATES
            ) {
                return null
            }
            val locators = observation.locatorCandidates.mapNotNull { locator ->
                WechatTargetLocatorDigest.compute(locator, locatorSalt)?.let { digest ->
                    WechatSemanticLocatorEvidence(digest, visibleToUser = true)
                }
            }
            val actions = List(observation.callEntryMatchCount) { index ->
                WechatSemanticActionNodeEvidence(
                    handle = index,
                    text = CONTACT_PROFILE_CALL_ENTRY,
                    visibleToUser = true,
                    enabled = true,
                    selfClickable = false,
                )
            }
            WechatSemanticContactProfileEvidence(
                locatorCandidates = locators,
                callEntryCandidates = actions,
                capturedAt = OffsetDateTime.now(),
            )
        } finally {
            observation.clear()
        }
    }

    override suspend fun readCallChoiceEvidence(): WechatSemanticCallChoiceEvidence? {
        val lines = captureWechatLines() ?: return null
        val observation = WechatVisualTextEvidenceRule.callChoice(lines)
        if (observation.voiceMatchCount !in 0..MAXIMUM_CANDIDATES ||
            observation.videoMatchCount !in 0..MAXIMUM_CANDIDATES
        ) {
            return null
        }
        val actions = buildList {
            repeat(observation.voiceMatchCount) { index ->
                add(visualAction(index, VOICE_CALL_TEXT))
            }
            repeat(observation.videoMatchCount) { index ->
                add(visualAction(MAXIMUM_CANDIDATES + index, VIDEO_CALL_TEXT))
            }
        }
        return WechatSemanticCallChoiceEvidence(actions, OffsetDateTime.now())
    }

    private suspend fun captureWechatLines(): List<WechatVisualTextLine>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isWechatForeground()) return null
        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }
            fun finish(value: List<WechatVisualTextLine>?) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(value)
                }
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(
                            screenshot: AccessibilityService.ScreenshotResult,
                        ) {
                            recognize(screenshot, ::finish)
                        }

                        override fun onFailure(errorCode: Int) {
                            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SCREENSHOT_FAILED, errorCode)
                            finish(null)
                        }
                    },
                )
            } catch (_: SecurityException) {
                finish(null)
            } catch (_: IllegalStateException) {
                finish(null)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recognize(
        screenshot: AccessibilityService.ScreenshotResult,
        finish: (List<WechatVisualTextLine>?) -> Unit,
    ) {
        val buffer = screenshot.hardwareBuffer
        val hardwareBitmap = runCatching {
            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
        }.getOrNull()
        val bitmap = runCatching {
            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        hardwareBitmap?.recycle()
        buffer.close()
        if (bitmap == null) {
            finish(null)
            return
        }
        WechatBitmapTextReader.read(bitmap) { lines ->
            try {
                WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SCREENSHOT_READY, lines?.size ?: -1)
                finish(lines.takeIf { isWechatForeground() })
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun visualAction(handle: Int, text: String) =
        WechatSemanticActionNodeEvidence(
            handle = handle,
            text = text,
            visibleToUser = true,
            enabled = true,
            selfClickable = false,
        )

    private fun isWechatForeground(): Boolean = runCatching {
        service.rootInActiveWindow?.packageName?.toString() ==
            WechatSemanticCallContract.WECHAT_PACKAGE
    }.getOrDefault(false)

    private companion object {
        const val CONTACT_PROFILE_CALL_ENTRY = "音视频通话"
        const val VOICE_CALL_TEXT = "语音通话"
        const val VIDEO_CALL_TEXT = "视频通话"
        const val MAXIMUM_CANDIDATES = 8
    }
}
