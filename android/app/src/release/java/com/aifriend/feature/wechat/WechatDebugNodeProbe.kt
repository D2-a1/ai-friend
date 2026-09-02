package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService

/** Release 固定空实现：不持有服务、不读取节点、不生成诊断文件。 */
object WechatDebugNodeProbe {
    fun attach(service: AccessibilityService) = Unit

    fun detach(service: AccessibilityService) = Unit
}
