package com.aifriend.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatAccessibilityServiceContractTest {

    @Suppress("DEPRECATION")
    @Test
    fun installedServiceKeepsRestrictedWechatReadOnlyEventContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedComponent = ComponentName(context, WechatAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.installedAccessibilityServiceList.firstOrNull { candidate ->
            val info = candidate.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expectedComponent
        }

        assertNotNull(service)
        service ?: return
        assertTrue(service.canRetrieveWindowContent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(service.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(service.isAccessibilityTool)
        }
        assertEquals(listOf(WECHAT_PACKAGE), service.packageNames?.toList())
        assertTrue(service.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        EXPECTED_EVENT_TYPES.forEach { eventType ->
            assertTrue(
                "missing accessibility event type $eventType",
                service.eventTypes and eventType != 0,
            )
        }
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val EXPECTED_EVENT_TYPES = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
        )
    }
}
