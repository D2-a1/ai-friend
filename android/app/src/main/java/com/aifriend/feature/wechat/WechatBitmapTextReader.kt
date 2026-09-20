package com.aifriend.feature.wechat

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 同帧局部双尺度复读；不接收目标微信号，不补字、不改大小写、不作模糊匹配。 */
object WechatLocatorRefinementRule {
    /** 只处理固定标签空白和已复现的单个前置竖线；账号字符逐字保留。 */
    fun normalizeLabel(text: String): String {
        var cursor = 0
        fun skipSpacing() {
            while (cursor < text.length && (text[cursor].isWhitespace() ||
                Character.isSpaceChar(text[cursor]) ||
                Character.getType(text[cursor]) == Character.FORMAT.toInt())) cursor++
        }
        skipSpacing()
        // 真机浅灰标签首笔被识别成额外的 |；只在完整固定标签前允许一次，
        // 不剥离任意文字/图标，也绝不删除账号中的竖线、空白或下划线。
        if (cursor < text.length && text[cursor] == '|') cursor++
        for (character in "微信号") {
            skipSpacing()
            if (cursor >= text.length || text[cursor] != character) return text
            cursor++
        }
        return "微信号" + text.substring(cursor)
    }

    fun merge(
        original: List<WechatVisualTextLine>,
        row: WechatVisualTextLine,
        first: CharArray?,
        second: CharArray?,
    ): List<WechatVisualTextLine>? {
        if (first == null || second == null || first.isEmpty() || !first.contentEquals(second)) return null
        val remaining = original.filter { line ->
            val overlap = minOf(row.bottom, line.bottom) - maxOf(row.top, line.top)
            overlap * 2 < minOf(row.bottom - row.top, line.bottom - line.top)
        }
        return remaining + row.copy(text = "微信号：" + first.concatToString())
    }
}

/** 本机同款 OCR。调用者持有 bitmap，直到回调结束才可回收；不落盘、不上传。 */
object WechatBitmapTextReader {
    fun read(bitmap: Bitmap, finish: (List<WechatVisualTextLine>?) -> Unit) {
        readOnce(bitmap) { original ->
            if (original == null) {
                finish(null)
                return@readOnce
            }
            val rows = original.filter { it.text.trim().startsWith("微信号") }
            if (rows.isEmpty()) {
                finish(original)
                return@readOnce
            }
            val row = rows.singleOrNull()
            if (row == null) {
                finish(null)
                return@readOnce
            }
            val padding = row.bottom - row.top
            val top = (row.top - padding).coerceAtLeast(0)
            val bottom = (row.bottom + padding).coerceAtMost(bitmap.height)
            // 有界内存；异常大页面不放大，更不能使用不可靠原图结果放行。
            if (padding <= 0 || bitmap.width > 2048 || bottom - top > 512) {
                finish(null)
                return@readOnce
            }
            val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
            readScaledLocator(crop, 2) { first ->
                readScaledLocator(crop, 3) { second ->
                    try {
                        finish(WechatLocatorRefinementRule.merge(original, row, first, second))
                    } finally {
                        first?.fill('\u0000')
                        second?.fill('\u0000')
                        crop.recycle()
                    }
                }
            }
        }
    }

    private fun readScaledLocator(crop: Bitmap, scale: Int, finish: (CharArray?) -> Unit) {
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
        readOnce(scaled) { lines ->
            val observation = lines?.let(WechatVisualTextEvidenceRule::contactProfile)
            try {
                finish(observation?.locatorCandidates?.singleOrNull()?.copyOf())
            } finally {
                observation?.clear()
                scaled.recycle()
            }
        }
    }

    private fun readOnce(bitmap: Bitmap, finish: (List<WechatVisualTextLine>?) -> Unit) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                try {
                    if (!task.isSuccessful) {
                        finish(null)
                        return@addOnCompleteListener
                    }
                    val lines = task.result.textBlocks.flatMap { it.lines }
                    if (lines.size > 128 || lines.any { it.text.length > 128 }) {
                        finish(null)
                        return@addOnCompleteListener
                    }
                    val sectionTop = lines.filter { it.text.trim() == "联系人" }
                        .singleOrNull()?.boundingBox?.top
                    val searchCancel = sectionTop?.let { top -> lines.filter {
                        it.text.trim() == "取消" && (it.boundingBox?.bottom ?: Int.MAX_VALUE) < top
                    }.singleOrNull()?.boundingBox }
                    finish(lines.mapNotNull { line ->
                        val b = line.boundingBox ?: return@mapNotNull null
                        if (b.width() <= 0 || b.height() <= 0 || b.left < 0 || b.top < 0 ||
                            b.right > bitmap.width || b.bottom > bitmap.height) return@mapNotNull null
                        val result = WechatVisualTextLine(WechatLocatorRefinementRule.normalizeLabel(line.text),
                            b.left, b.top, b.right, b.bottom)
                        if (sectionTop == null || searchCancel == null || b.bottom >= sectionTop ||
                            b.right >= searchCancel.left || minOf(b.bottom, searchCancel.bottom) <=
                            maxOf(b.top, searchCancel.top)) return@mapNotNull result
                        val parts = line.elements.mapNotNull elementPart@ { element ->
                            val box = element.boundingBox ?: return@elementPart null
                            if (box.left < 0 || box.top < 0 || box.right > bitmap.width ||
                                box.bottom > bitmap.height || box.width() <= 0 || box.height() <= 0) null
                            else WechatVisualTextLine(element.text, box.left, box.top, box.right, box.bottom)
                        }
                        if (parts.size != 2 || parts[0].text.length != 1 || parts.any {
                                (it.right - it.left).toLong() * (it.bottom - it.top) > 131_072L
                            }) return@mapNotNull result
                        fun pixelStats(part: WechatVisualTextLine): Pair<Int, Int> {
                            var minimum = 255
                            var dark = 0
                            for (y in part.top until part.bottom) for (x in part.left until part.right) {
                                val pixel = bitmap.getPixel(x, y)
                                val channel = minOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
                                minimum = minOf(minimum, channel)
                                if (channel < 100) dark++
                            }
                            return minimum to dark
                        }
                        // 用户输入的黑色 Q/额外文字不能被当作图标；无法分离时保留原文并拒绝。
                        WechatSearchQueryArtworkRule.separate(result, parts,
                            pixelStats(parts[0]).first, pixelStats(parts[1]).second)
                    }.takeIf { it.isNotEmpty() })
                } finally { recognizer.close() }
            }
        } catch (_: Exception) {
            recognizer.close()
            finish(null)
        }
    }
}
