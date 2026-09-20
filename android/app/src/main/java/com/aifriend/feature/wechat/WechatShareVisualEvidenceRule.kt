package com.aifriend.feature.wechat

/** 分享页专用同帧校验；只接受精确查询、联系人分区及唯一微信号所在行。 */
object WechatShareVisualEvidenceRule {
    fun searchResult(
        lines: List<WechatVisualTextLine>,
        expected: CharArray,
        point: WechatCalibrationPixelPoint,
    ): Boolean {
        if (expected.isEmpty() || expected.any { it == '\u0000' }) return false
        val section = lines.filter { it.text.trim() == "联系人" }.singleOrNull() ?: return false
        val query = lines.filter { it.bottom < section.top && it.text.trim() == expected.concatToString() }
            .singleOrNull() ?: return false
        if (lines.count { it.top < section.top && it.text.trim() == "取消" } != 1) return false
        val row = lines.filter { it.text.trim().startsWith("微信号") }.singleOrNull() ?: return false
        if (row.top <= section.bottom || row.top <= query.bottom) return false
        val observation = WechatVisualTextEvidenceRule.contactProfile(listOf(row))
        try {
            if (observation.locatorCandidates.singleOrNull()?.contentEquals(expected) != true) return false
        } finally { observation.clear() }
        val height = row.bottom - row.top
        // 行的上方是备注/头像；不能让资料页正文或下方最近联系人坐标冒充结果。
        return height > 0 && point.x > 0 && point.y > section.bottom &&
            point.y in (row.top - height * 3)..(row.bottom + height)
    }

    fun sendButton(lines: List<WechatVisualTextLine>, point: WechatCalibrationPixelPoint): Boolean {
        val send = lines.filter { it.text.trim() == "发送" }.singleOrNull() ?: return false
        // 搜索栏的“取消”可留在遮罩背景中，只接受与发送按钮同排的唯一取消按钮。
        val cancel = lines.filter {
            it.text.trim() == "取消" &&
                minOf(send.bottom, it.bottom) > maxOf(send.top, it.top)
        }.singleOrNull() ?: return false
        val height = send.bottom - send.top
        val overlap = minOf(send.bottom, cancel.bottom) - maxOf(send.top, cancel.top)
        if (height <= 0 || overlap <= 0 || cancel.right >= send.left) return false
        return point.x in (send.left - height)..(send.right + height) &&
            point.y in (send.top - height)..(send.bottom + height)
    }
}
