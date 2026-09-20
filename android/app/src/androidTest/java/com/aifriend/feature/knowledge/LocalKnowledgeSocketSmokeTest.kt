package com.aifriend.feature.knowledge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aifriend.BuildConfig
import com.aifriend.app.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit synthetic-account UI smoke against the loopback backend, never a release endpoint. */
class LocalKnowledgeSocketSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) {
        try { ui.waitUntil(30_000) { exists(text) } }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("Missing state: $text\n" + ui.onRoot().printToString(), failure)
        }
    }
    private fun click(text: String) {
        waitFor(text)
        ui.waitUntil(30_000) { ui.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(text).performScrollTo().assertIsEnabled().performClick()
    }

    @Test fun syntheticLoginKnowledgeCitationAndGraphConsent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localSocketSmoke") == "true")
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:18080/api/v1/")
        ui.waitUntil(30_000) { exists("进入体验版") || exists("我的") }
        if (exists("我的")) {
            ui.onNodeWithText("我的").performClick()
            click("退出本机登录")
            waitFor("确认退出")
            ui.onNodeWithText("确认退出").performClick()
        }
        click("进入体验版")
        ui.waitUntil(30_000) { exists("同意并继续") || exists("我的") }
        if (exists("同意并继续")) click("同意并继续")
        waitFor("我的")
        ui.onNodeWithText("我的").performClick()
        click("知识问答与关系查询")
        click("公开知识问答")
        waitFor("可以输入")
        ui.onNode(hasSetTextAction()).performScrollTo().performTextInput("绿灯按钮")
        click("提交")
        waitFor("知识原文摘录")
        ui.onNodeWithText("知识原文摘录").performScrollTo().assertIsDisplayed()
        ui.onAllNodesWithText("绿灯按钮用于打开夜间模式。", substring = true).assertCountEquals(2)
        click("取消并清空本次会话")
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("远端关闭尚未确认", substring = true).fetchSemanticsNodes().isEmpty()
        }
        // The fresh synthetic owner has no contacts; independent graph consent must gate even a read.
        ui.onNodeWithText("亲友关系查询").performScrollTo().assertIsNotEnabled()
        ui.onAllNodesWithText("查看并选择同意")[1].performScrollTo().performClick()
        waitFor("明确同意")
        ui.onNodeWithText("明确同意").performClick()
        waitFor("亲友关系查询：已同意")
        click("亲友关系查询")
        waitFor("可以输入")
        click("列出已绑定亲友")
        waitFor("没有找到符合条件的已绑定亲友。")
        click("撤回亲友关系查询")
        waitFor("确认撤回")
        ui.onNodeWithText("确认撤回").performClick()
        waitFor("亲友关系查询：未同意")
        ui.onNodeWithText("亲友关系查询").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("知识问答外部模型处理：未同意").performScrollTo().assertIsDisplayed()
    }
}
