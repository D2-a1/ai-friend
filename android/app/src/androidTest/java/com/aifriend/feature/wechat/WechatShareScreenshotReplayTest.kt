package com.aifriend.feature.wechat

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 只对显式提供的临时截图运行生产校验；绝不连接辅助服务或执行微信动作。 */
class WechatShareScreenshotReplayTest {
    @Test fun productionReaderValidatesAuthorizedShareScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "share-replay.png")
        assumeTrue("requires explicit screenshot", file.isFile)
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            val future = CompletableFuture<List<WechatVisualTextLine>?>()
            WechatBitmapTextReader.read(bitmap) { future.complete(it) }
            val lines = checkNotNull(future.get(20, TimeUnit.SECONDS))
            val point = WechatCalibrationPixelPoint(
                args.getString("pointX")!!.toInt(), args.getString("pointY")!!.toInt())
            val send = args.getString("kind") == "send"
            val expected = args.getString("expectedLocator").orEmpty().toCharArray()
            try {
                val accepted = if (send) WechatShareVisualEvidenceRule.sendButton(lines, point)
                    else WechatShareVisualEvidenceRule.searchResult(lines, expected, point)
                instrumentation.sendStatus(0, Bundle().apply {
                    if (!send && expected.isNotEmpty()) putString("fixedLabelCodePoints",
                        lines.filter { it.text.contains("微信") && it.text.contains(expected.concatToString()) }
                            .joinToString(";") { it.text.substringBefore(expected.concatToString())
                                .map { character -> character.code }.joinToString(",") })
                    putString("shareEvidence", "kind=${if (send) "send" else "search"} accepted=$accepted" +
                        " locatorRows=${lines.count { it.text.startsWith("微信号") }}" +
                        " exactQuery=${lines.count { it.text.trim() == expected.concatToString() }}")
                    putString("lineShape", lines.joinToString(";") {
                        "n=${it.text.length},wx=${it.text.contains("微信")},label=${it.text.contains("号")}," +
                            "query=${expected.isNotEmpty() && it.text.contains(expected.concatToString())}," +
                            "section=${it.text.trim() == "联系人"},cancel=${it.text.trim() == "取消"}," +
                            "send=${it.text.trim() == "发送"},box=${it.left},${it.top},${it.right},${it.bottom}"
                    })
                })
                assertTrue("Current production share rule must accept authorized screenshot", accepted)
                if (!send) {
                    assertFalse(WechatShareVisualEvidenceRule.searchResult(lines, "Wrong_01".toCharArray(), point))
                    assertFalse(WechatShareVisualEvidenceRule.searchResult(lines, expected, point.copy(y = bitmap.height / 2)))
                }
            } finally { expected.fill('\u0000') }
        } finally { bitmap.recycle(); file.delete() }
    }
}
