package com.aifriend.feature.wechat

/** 只保存分享弹窗固定按钮的几何属性，不接受联系人或消息正文。 */
internal data class WechatShareButtonNode(
    val label: Label,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
) {
    enum class Label { SEND, CANCEL }
}

/** 节点路径的最终按钮验证；调用方已筛选可见、启用且属于微信的节点。 */
internal object WechatShareSendNodeRule {
    enum class Decision {
        VERIFIED, SEND_NOT_UNIQUE, CANCEL_NOT_UNIQUE, INVALID_GEOMETRY,
        SEND_NOT_CLICKABLE, POINT_OUTSIDE,
    }

    fun verify(nodes: List<WechatShareButtonNode>, point: WechatCalibrationPixelPoint): Decision {
        val send = nodes.filter { it.label == WechatShareButtonNode.Label.SEND }.singleOrNull()
            ?: return Decision.SEND_NOT_UNIQUE
        if (send.right <= send.left || send.bottom <= send.top) return Decision.INVALID_GEOMETRY
        // 背景搜索栏仍可有“取消”；必须是弹窗内与发送同排的唯一取消。
        val cancel = nodes.filter {
            it.label == WechatShareButtonNode.Label.CANCEL &&
                minOf(it.bottom, send.bottom) > maxOf(it.top, send.top)
        }.singleOrNull() ?: return Decision.CANCEL_NOT_UNIQUE
        if (cancel.right <= cancel.left || cancel.bottom <= cancel.top || cancel.right >= send.left) {
            return Decision.INVALID_GEOMETRY
        }
        if (!send.clickable) return Decision.SEND_NOT_CLICKABLE
        return if (point.x >= send.left && point.x < send.right &&
            point.y >= send.top && point.y < send.bottom) Decision.VERIFIED else Decision.POINT_OUTSIDE
    }
}
