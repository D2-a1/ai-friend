package com.aifriend.app.ui.home

import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ContactStatus
import com.aifriend.feature.contact.ui.ContactManagementUiState
import com.aifriend.feature.contact.ui.DEBUG_DEMO_RELATIONSHIP
import com.aifriend.feature.guardian.GuardianMode
import com.aifriend.feature.guardian.GuardianStatus
import com.aifriend.feature.settings.CapabilityReadState
import com.aifriend.feature.settings.SettingsCapabilityStatus
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页体验任务只开放当前唯一可执行下一步。 */
class HomeTaskPresentationTest {

    @Test
    fun releaseKeepsFormalTaskEntry() {
        val presentation = ContactManagementUiState().homeTaskPresentation(demoEnabled = false)

        assertEquals(HomeTaskAction.START_TASK, presentation.action)
        assertEquals("开始说话", presentation.actionLabel)
    }

    @Test
    fun loadingDisablesTaskEntry() {
        val presentation = ContactManagementUiState(
            isInitialLoading = true,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.NONE, presentation.action)
        assertEquals("正在检查", presentation.actionLabel)
    }

    @Test
    fun refreshingDisablesTaskEntryUntilLatestStateArrives() {
        val presentation = ContactManagementUiState(
            isInitialLoading = false,
            isRefreshing = true,
            contacts = listOf(demoContact(status = ContactStatus.ACTIVE, aliasCount = 1)),
            safetyCommandsReady = true,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.NONE, presentation.action)
        assertEquals("正在更新", presentation.actionLabel)
    }

    @Test
    fun preparingDemoContactDisablesTaskEntry() {
        val presentation = ContactManagementUiState(
            isInitialLoading = false,
            isPreparingDemoContact = true,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.NONE, presentation.action)
        assertEquals("正在更新体验准备", presentation.title)
    }

    @Test
    fun contactFailureRoutesToExistingSetupWithoutClaimingContactIsMissing() {
        val presentation = ContactManagementUiState(
            isInitialLoading = false,
            errorMessage = "联系人加载失败，请稍后重试",
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.OPEN_DEMO_SETUP, presentation.action)
        assertEquals("体验准备暂时无法继续", presentation.title)
        assertEquals("请打开准备步骤，查看提示后重新操作。", presentation.support)
    }

    @Test
    fun readinessFailureRoutesToExistingSetupWithoutOldFamilyPageCopy() {
        val presentation = ContactManagementUiState(
            isInitialLoading = false,
            contacts = listOf(demoContact(status = ContactStatus.ACTIVE, aliasCount = 1)),
            demoReadinessCheckFailed = true,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.OPEN_DEMO_SETUP, presentation.action)
        assertEquals("请打开准备步骤，重新检查安全指令状态。", presentation.support)
    }

    @Test
    fun missingDemoContactRoutesToSetup() {
        val presentation = ContactManagementUiState().homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.OPEN_DEMO_SETUP, presentation.action)
        assertEquals("先准备体验联系人", presentation.title)
    }

    @Test
    fun missingAliasRoutesToSetup() {
        val presentation = ContactManagementUiState(
            contacts = listOf(demoContact(status = ContactStatus.ACTIVE_NO_ALIAS, aliasCount = 0)),
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.OPEN_DEMO_SETUP, presentation.action)
        assertEquals("下一步设置体验称呼", presentation.title)
    }

    @Test
    fun missingSafetyCommandsRoutesToSetup() {
        val presentation = ContactManagementUiState(
            contacts = listOf(demoContact(status = ContactStatus.ACTIVE, aliasCount = 1)),
            safetyCommandsReady = false,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.OPEN_DEMO_SETUP, presentation.action)
        assertEquals("下一步录制安全指令", presentation.title)
    }

    @Test
    fun completePreparationStartsDemoTask() {
        val presentation = ContactManagementUiState(
            contacts = listOf(demoContact(status = ContactStatus.ACTIVE, aliasCount = 1)),
            safetyCommandsReady = true,
        ).homeTaskPresentation(demoEnabled = true)

        assertEquals(HomeTaskAction.START_TASK, presentation.action)
        assertEquals("开始体验任务", presentation.actionLabel)
    }

    @Test
    fun shortcutsKeepExistingOrderAndOnlyUseFirstFourActiveContacts() {
        val shortcuts = ContactManagementUiState(
            contacts = listOf(
                contact(id = "first", remark = "妈妈"),
                contact(id = "pending", status = ContactStatus.ACTIVE_NO_ALIAS),
                contact(id = "second", displayName = "爸爸"),
                contact(id = "third", remark = "姐姐"),
                contact(id = "fourth", remark = "弟弟"),
                contact(id = "fifth", remark = "妹妹"),
            ),
        ).homeContactShortcuts()

        assertEquals(listOf("first", "second", "third", "fourth"), shortcuts.map { it.contactId })
        assertEquals(listOf("妈妈", "爸爸", "姐姐", "弟弟"), shortcuts.map { it.label })
    }

    @Test
    fun shortcutUsesRemarkBeforeDisplayNameAndProvidesChineseFallback() {
        val shortcuts = ContactManagementUiState(
            contacts = listOf(
                contact(id = "remark", remark = "外婆", displayName = "旧名称"),
                contact(id = "display", displayName = "舅舅"),
                contact(id = "fallback"),
            ),
        ).homeContactShortcuts()

        assertEquals(listOf("外婆", "舅舅", "当前已绑定亲友"), shortcuts.map { it.label })
        assertEquals(listOf("外", "舅", "当"), shortcuts.map { it.avatarText })
    }

    @Test
    fun shortcutUsesSavedAliasWhenProfileNamesAreBlank() {
        val state = ContactManagementUiState(contacts = listOf(
            contact(id = "alias", remark = " ", displayName = "").copy(
                aliases = listOf(alias(" "), alias("老三"), alias("三弟")),
            ),
        ))

        assertEquals(listOf(HomeContactShortcut("alias", "老三", "老")), state.homeContactShortcuts())
    }

    @Test
    fun shortcutKeepsProfileNamePriorityOverAliases() {
        val state = ContactManagementUiState(contacts = listOf(
            contact(id = "remark", remark = "妈妈", displayName = "微信名称")
                .copy(aliases = listOf(alias("母亲"))),
            contact(id = "name", remark = " ", displayName = "爸爸")
                .copy(aliases = listOf(alias("父亲"))),
        ))

        assertEquals(listOf("妈妈", "爸爸"), state.homeContactShortcuts().map { it.label })
    }

    @Test
    fun refreshedAliasUpdatesShortcutLabelAndAvatarWithoutChangingIdentity() {
        val original = contact(id = "same-contact").copy(aliases = listOf(alias("老三")))
        val initial = ContactManagementUiState(contacts = listOf(original))
        val refreshed = initial.copy(contacts = listOf(original.copy(
            aliases = listOf(alias("三弟")), version = original.version + 1,
        )))

        assertEquals(HomeContactShortcut("same-contact", "老三", "老"), initial.homeContactShortcuts().single())
        assertEquals(HomeContactShortcut("same-contact", "三弟", "三"), refreshed.homeContactShortcuts().single())
    }

    @Test
    fun missingOrBlankAliasesUseSharedFallbackWithoutLeakingAnotherContactName() {
        val state = ContactManagementUiState(contacts = listOf(
            contact(id = "named").copy(aliases = listOf(alias("姐姐"))),
            contact(id = "missing").copy(aliases = null),
            contact(id = "blank").copy(aliases = listOf(alias(" "))),
        ))

        assertEquals(listOf("姐姐", "当前已绑定亲友", "当前已绑定亲友"),
            state.homeContactShortcuts().map { it.label })
    }

    private fun alias(text: String): ContactAlias = ContactAlias(
        id = "alias-$text",
        displayText = text,
        dialectCode = "zh-Hans-CN-x-wugang",
        dialectPackageVersion = "basic-experience-v1",
        modelVersion = "mfcc-dtw-basic-v1",
        thresholdVersion = "basic-personal-v1",
        compatibility = AliasCompatibility.COMPATIBLE,
        createdAt = NOW,
    )

    @Test
    fun capabilitySummaryUsesOnlyProvidedFactsAndActiveGuardianState() {
        val items = homeCapabilityItems(
            capabilities = SettingsCapabilityStatus(
                network = CapabilityReadState.AVAILABLE,
                microphone = CapabilityReadState.UNAVAILABLE,
                notifications = CapabilityReadState.UNKNOWN,
                restrictedWechatAccessibility = CapabilityReadState.AVAILABLE,
                accountSession = CapabilityReadState.AVAILABLE,
                batteryOptimizationExemption = CapabilityReadState.UNKNOWN,
            ),
            guardianStatus = GuardianStatus(GuardianMode.SLEEPING),
        )

        assertEquals(listOf("网络", "麦克风", "微信辅助", "小友守护"), items.map { it.title })
        assertEquals(
            listOf("网络可用", "麦克风未允许", "辅助服务已开启", "守护已开启"),
            items.map { it.detail },
        )
        assertEquals(
            listOf(
                CapabilityReadState.AVAILABLE,
                CapabilityReadState.UNAVAILABLE,
                CapabilityReadState.AVAILABLE,
                CapabilityReadState.AVAILABLE,
            ),
            items.map { it.state },
        )
    }

    @Test
    fun missingCapabilityFactsAndStartingGuardianRemainUnknown() {
        val items = homeCapabilityItems(
            capabilities = null,
            guardianStatus = GuardianStatus(GuardianMode.STARTING),
        )

        assertEquals(
            listOf("网络状态未知", "麦克风状态未知", "辅助服务状态未知", "正在开启守护"),
            items.map { it.detail },
        )
        assertEquals(List(4) { CapabilityReadState.UNKNOWN }, items.map { it.state })
    }

    private fun contact(
        id: String,
        status: ContactStatus = ContactStatus.ACTIVE,
        remark: String? = null,
        displayName: String? = null,
    ): Contact = Contact(
        id = id,
        status = status,
        aliasCount = if (status == ContactStatus.ACTIVE) 1 else 0,
        version = 1,
        createdAt = NOW,
        updatedAt = NOW,
        remark = remark,
        displayName = displayName,
        aliases = emptyList(),
    )

    private fun demoContact(
        status: ContactStatus,
        aliasCount: Int,
    ): Contact = Contact(
        id = "demo",
        status = status,
        aliasCount = aliasCount,
        version = 1,
        createdAt = NOW,
        updatedAt = NOW,
        relationship = DEBUG_DEMO_RELATIONSHIP,
        aliases = emptyList(),
    )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-29T08:00:00Z")
    }
}
