package com.aifriend.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.aifriend.feature.wechat.AndroidWechatSampleCaptureCoordinator
import com.aifriend.feature.wechat.WechatAccessibilityPageReader
import com.aifriend.feature.wechat.WechatDebugNodeProbe
import com.aifriend.feature.wechat.WechatSampleCaptureTarget
import dagger.hilt.android.AndroidEntryPoint
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** ADB shell 专用 Debug 预检入口；Manifest 使用 DUMP 权限拒绝普通应用调用。 */
@AndroidEntryPoint
class WechatNodeProbeReceiver : BroadcastReceiver() {
    @Inject
    lateinit var sampleCaptureCoordinator: AndroidWechatSampleCaptureCoordinator

    @Inject
    lateinit var pageReader: WechatAccessibilityPageReader

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            resultData = "INVALID_ACTION"
            return
        }
        val stage = intent.getStringExtra(EXTRA_STAGE).orEmpty()
        if (stage == CONTACT_PROFILE_DIAGNOSTIC_STAGE) {
            resultData = WechatDebugNodeProbe.diagnoseContactProfile()
            return
        }
        if (stage == VISUAL_CONTACT_PROFILE_DIAGNOSTIC_STAGE) {
            diagnoseVisualContactProfile()
            return
        }
        val target = RULE_SAMPLE_STAGES[stage]
        if (target != null) {
            resultData = captureRuleSample(target)
            return
        }
        val result = if (stage == CLEAR_STAGE) {
            WechatDebugNodeProbe.clear(context.applicationContext)
        } else {
            WechatDebugNodeProbe.capture(context.applicationContext, stage)
        }
        resultData = result.name
    }

    private fun captureRuleSample(target: WechatSampleCaptureTarget): String {
        if (!sampleCaptureCoordinator.begin(target)) return "SAMPLE_BEGIN_FAILED"
        val capturedAt = OffsetDateTime.now()
        val sample = WechatDebugNodeProbe.captureRuleSample(pageReader, target, capturedAt)
            ?: run {
                sampleCaptureCoordinator.interrupt()
                return "SAMPLE_READ_FAILED"
            }
        sampleCaptureCoordinator.publish(sample, capturedAt)
        val state = sampleCaptureCoordinator.state.value
        return listOf(
            "SAMPLE_PUBLISHED",
            target.name,
            state.completedSamples.toString(),
            state.complete.toString(),
            state.consistent?.toString() ?: "pending",
        ).joinToString(":")
    }

    private fun diagnoseVisualContactProfile() {
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val finish: (String) -> Unit = { diagnostic ->
            if (finished.compareAndSet(false, true)) {
                pendingResult.resultData = diagnostic
                pendingResult.finish()
            }
        }
        mainHandler.postDelayed(
            { finish("OCR:TIMEOUT") },
            VISUAL_DIAGNOSTIC_TIMEOUT_MILLIS,
        )
        WechatDebugNodeProbe.diagnoseVisualContactProfile(finish)
    }

    private companion object {
        const val ACTION = "com.aifriend.debug.WECHAT_NODE_PROBE"
        const val EXTRA_STAGE = "stage"
        const val CLEAR_STAGE = "CLEAR"
        const val CONTACT_PROFILE_DIAGNOSTIC_STAGE = "DIAG_CONTACT_PROFILE"
        const val VISUAL_CONTACT_PROFILE_DIAGNOSTIC_STAGE = "DIAG_OCR_CONTACT_PROFILE"
        const val VISUAL_DIAGNOSTIC_TIMEOUT_MILLIS = 20_000L
        val RULE_SAMPLE_STAGES = WechatSampleCaptureTarget.entries.associateBy { target ->
            "RULE_" + target.name
        }
    }
}
