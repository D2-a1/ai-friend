package com.aifriend.feature.wechat

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式本机截图回放；不请求网络、辅助服务权限，也不发送消息或拨号。 */
class WechatVisualProfileReplayTest {
    @Test
    fun authorizedScreenshotMatchesExpectedLocator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val expected = args.getString("expectedLocator")
        assumeTrue("requires explicit authorized input", !expected.isNullOrBlank())
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "profile-replay.png")
        assumeTrue("requires temporary screenshot", file.isFile)
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                WechatVisualTextLine(line.text, rect.left, rect.top, rect.right, rect.bottom)
            }
            val observation = WechatVisualTextEvidenceRule.contactProfile(lines)
            try {
                val actual = observation.locatorCandidates.singleOrNull()?.concatToString()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("diagnostic", "candidates=${observation.locatorCandidates.size}" +
                        " actualLength=${actual?.length} expectedLength=${expected?.length}" +
                        " exact=${actual == expected} caseOnly=${actual?.equals(expected, true)}" +
                        " underscores=${actual?.count { it == '_' }}" +
                        " callEntries=${observation.callEntryMatchCount}" +
                        " onlyMissingUnderscore=${actual == expected?.replace("_", "")}")
                })
                val row = lines.singleOrNull { it.text.contains("微信号") }
                if (row != null) {
                    val padding = row.bottom - row.top
                    val top = (row.top - padding).coerceAtLeast(0)
                    val bottom = (row.bottom + padding).coerceAtMost(bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                    try {
                        for (scale in listOf(1, 2, 3)) {
                            val scaled = if (scale == 1) crop else
                                Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
                            try {
                                val variant = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0)), 20, TimeUnit.SECONDS)
                                val tokens = variant.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val b = line.boundingBox ?: return@mapNotNull null
                                    WechatVisualTextLine(line.text, b.left, b.top, b.right, b.bottom)
                                }
                                val parsed = WechatVisualTextEvidenceRule.contactProfile(tokens)
                                try {
                                    val value = parsed.locatorCandidates.singleOrNull()?.concatToString()
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("variant", "cropScale=$scale length=${value?.length}" +
                                            " exact=${value == expected} underscores=${value?.count { it == '_' }}")
                                    })
                                } finally { parsed.clear() }
                            } finally { if (scaled !== crop) scaled.recycle() }
                        }
                    } finally { crop.recycle() }
                }
                val refinedResult = CompletableFuture<List<WechatVisualTextLine>?>()
                WechatBitmapTextReader.read(bitmap) { refinedResult.complete(it) }
                val refined = WechatVisualTextEvidenceRule.contactProfile(
                    checkNotNull(refinedResult.get(20, TimeUnit.SECONDS)),
                )
                try {
                    val resolved = refined.locatorCandidates.singleOrNull()?.concatToString()
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("productionRefinement", "exact=${resolved == expected}" +
                            " callEntries=${refined.callEntryMatchCount}")
                    })
                    assertTrue("Refined OCR must exactly match authorized target", resolved == expected)
                    assertTrue("Call entry must remain unique", refined.callEntryMatchCount == 1)
                } finally { refined.clear() }
            } finally {
                observation.clear()
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
            file.delete()
        }
    }
}
