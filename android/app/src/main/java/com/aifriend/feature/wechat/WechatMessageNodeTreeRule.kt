package com.aifriend.feature.wechat

/** 仅保留空壳判定所需布尔特征，不持有节点、文本、描述或联系人信息。 */
internal data class WechatMessageNodeShape(
    val belongsToWechat: Boolean,
    val hasText: Boolean,
    val hasDescription: Boolean,
    val editable: Boolean,
    val clickable: Boolean,
)

/** 调用前必须完成有界遍历；读取失败或截断的树不能走此回退。 */
internal object WechatMessageNodeTreeRule {
    fun canUseVisualFallback(nodes: List<WechatMessageNodeShape>): Boolean =
        nodes.isNotEmpty() && nodes.all {
            it.belongsToWechat && !it.hasText && !it.hasDescription && !it.editable && !it.clickable
        }
}
