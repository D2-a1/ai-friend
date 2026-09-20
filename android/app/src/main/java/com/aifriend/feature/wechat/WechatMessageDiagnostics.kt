package com.aifriend.feature.wechat

/** 最近一次消息尝试的有界内存诊断。只接受固定枚举和计数，不接收账号、正文或录音。 */
object WechatMessageDiagnostics {
    enum class Event {
        AUDIO_BYTES, TEXT_STARTED, SDK_ACCEPTED, SELECTION_ACCEPTED, SDK_CALLBACK,
        ADMISSION_REJECTED, REQUEST_INVALID, TAP_RESULT, SEARCH_CHECK, SEND_CHECK,
        SCREENSHOT_FAILED, SCREENSHOT_READY, EXECUTOR_FINISHED,
        MESSAGE_NODE_COUNTS, SEND_NODE_CHECK, SEND_VISUAL_FALLBACK, MESSAGE_TREE_REJECTED,
        MESSAGE_NODE_SEMANTICS,
    }
    private val entries = ArrayDeque<String>()
    private var started = System.nanoTime()

    @Synchronized fun begin(audioBytes: Int) {
        entries.clear()
        started = System.nanoTime()
        record(Event.AUDIO_BYTES, audioBytes)
    }

    @Synchronized fun record(event: Event, first: Int = 0, second: Int = 0) =
        append("${event.name} first=$first second=$second")

    @Synchronized fun stage(stage: WechatMessageSelectionStage) = append("stage=${stage.name}")

    @Synchronized fun point(target: WechatCalibrationTarget, point: WechatCalibrationPixelPoint) =
        append("point=${target.name} x=${point.x} y=${point.y}")

    @Synchronized fun snapshot(): List<String> = entries.toList()

    private fun append(value: String) {
        if (entries.size >= 64) entries.removeFirst()
        entries.addLast("elapsedMs=${(System.nanoTime() - started) / 1_000_000} $value")
    }
}
