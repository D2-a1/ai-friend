package com.aifriend.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aifriend.app.ui.AiFriendApp
import com.aifriend.app.ui.AiFriendViewModel
import com.aifriend.core.design.AiFriendTheme
import com.aifriend.feature.contact.ui.ContactManagementViewModel
import com.aifriend.feature.contact.alias.AliasEnrollmentViewModel
import com.aifriend.feature.voice.safety.SafetyCommandEnrollmentViewModel
import com.aifriend.feature.task.TaskViewModel
import com.aifriend.feature.task.RecentTaskResultsViewModel
import com.aifriend.feature.guardian.GuardianViewModel
import com.aifriend.feature.guardian.wake.WakeWordEnrollmentViewModel
import com.aifriend.feature.privacy.TaskHistoryDeletionViewModel
import com.aifriend.feature.privacy.AccountClosureViewModel
import com.aifriend.feature.collection.VoiceCollectionViewModel
import com.aifriend.feature.settings.SettingsViewModel
import com.aifriend.feature.help.HelpViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 应用入口。
 *
 * @author codex
 * @since 2026-07-25
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AiFriendViewModel by viewModels()
    private val contactManagementViewModel: ContactManagementViewModel by viewModels()
    private val aliasEnrollmentViewModel: AliasEnrollmentViewModel by viewModels()
    private val safetyCommandEnrollmentViewModel: SafetyCommandEnrollmentViewModel by viewModels()
    private val taskViewModel: TaskViewModel by viewModels()
    private val recentTaskResultsViewModel: RecentTaskResultsViewModel by viewModels()
    private val taskHistoryDeletionViewModel: TaskHistoryDeletionViewModel by viewModels()
    private val accountClosureViewModel: AccountClosureViewModel by viewModels()
    private val guardianViewModel: GuardianViewModel by viewModels()
    private val wakeWordEnrollmentViewModel: WakeWordEnrollmentViewModel by viewModels()
    private val voiceCollectionViewModel: VoiceCollectionViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val helpViewModel: HelpViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()
            AiFriendTheme(
                fontLevel = settingsState.settings.fontLevel,
                highContrast = settingsState.settings.highContrast,
            ) {
                AiFriendApp(
                    viewModel = viewModel,
                    contactManagementViewModel = contactManagementViewModel,
                    aliasEnrollmentViewModel = aliasEnrollmentViewModel,
                    safetyCommandEnrollmentViewModel = safetyCommandEnrollmentViewModel,
                    taskViewModel = taskViewModel,
                    recentTaskResultsViewModel = recentTaskResultsViewModel,
                    taskHistoryDeletionViewModel = taskHistoryDeletionViewModel,
                    accountClosureViewModel = accountClosureViewModel,
                    guardianViewModel = guardianViewModel,
                    wakeWordEnrollmentViewModel = wakeWordEnrollmentViewModel,
                    voiceCollectionViewModel = voiceCollectionViewModel,
                    settingsViewModel = settingsViewModel,
                    helpViewModel = helpViewModel,
                )
            }
        }
    }
}
