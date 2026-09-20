package com.aifriend.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aifriend.app.ui.family.FamilyHubScreen
import com.aifriend.app.ui.home.HomeScreen
import com.aifriend.app.ui.navigation.MainBottomNavigation
import com.aifriend.app.ui.navigation.MainDestination
import com.aifriend.app.ui.profile.ProfileScreen
import com.aifriend.feature.knowledge.KnowledgeViewModel
import com.aifriend.feature.knowledge.KnowledgeRoute
import com.aifriend.app.ui.welcome.WelcomeStateContent
import com.aifriend.BuildConfig
import com.aifriend.feature.collection.VoiceCollectionRoute
import com.aifriend.feature.collection.VoiceCollectionViewModel
import com.aifriend.feature.contact.alias.AliasEnrollmentRoute
import com.aifriend.feature.contact.alias.AliasEnrollmentViewModel
import com.aifriend.feature.contact.ui.ContactManagementScreen
import com.aifriend.feature.contact.ui.ContactManagementViewModel
import com.aifriend.feature.guardian.GuardianServiceController
import com.aifriend.feature.guardian.GuardianViewModel
import com.aifriend.feature.guardian.awaitGuardianTaskHandoff
import com.aifriend.feature.guardian.wake.WakeWordEnrollmentRoute
import com.aifriend.feature.guardian.wake.WakeWordEnrollmentViewModel
import com.aifriend.feature.help.HelpRoute
import com.aifriend.feature.help.HelpViewModel
import com.aifriend.feature.privacy.AccountClosureRoute
import com.aifriend.feature.privacy.AccountClosureViewModel
import com.aifriend.feature.privacy.TaskHistoryDeletionRoute
import com.aifriend.feature.privacy.TaskHistoryDeletionViewModel
import com.aifriend.feature.settings.SettingsRoute
import com.aifriend.feature.settings.SettingsViewModel
import com.aifriend.feature.settings.WechatSampleCaptureRoute
import com.aifriend.feature.task.TaskRoute
import com.aifriend.feature.task.TaskViewModel
import com.aifriend.feature.task.decision.TaskDecisionEnrollmentRoute
import com.aifriend.feature.task.decision.TaskDecisionEnrollmentViewModel
import com.aifriend.feature.task.RecentTaskResultsRoute
import com.aifriend.feature.task.RecentTaskResultsViewModel
import com.aifriend.feature.voice.safety.SafetyCommandEnrollmentRoute
import com.aifriend.feature.voice.safety.SafetyCommandEnrollmentViewModel/**
 * 应用根界面。第一批开发展示登录恢复、基础身份授权和安全就绪状态。
 *
 * @author codex
 * @since 2026-07-25
 */
@Composable
fun AiFriendApp(
    viewModel: AiFriendViewModel,
    contactManagementViewModel: ContactManagementViewModel,
    aliasEnrollmentViewModel: AliasEnrollmentViewModel,
    safetyCommandEnrollmentViewModel: SafetyCommandEnrollmentViewModel,
    taskHistoryDeletionViewModel: TaskHistoryDeletionViewModel,
    accountClosureViewModel: AccountClosureViewModel,
    taskViewModel: TaskViewModel,
    taskDecisionEnrollmentViewModel: TaskDecisionEnrollmentViewModel,
    recentTaskResultsViewModel: RecentTaskResultsViewModel,
    guardianViewModel: GuardianViewModel,
    wakeWordEnrollmentViewModel: WakeWordEnrollmentViewModel,
    voiceCollectionViewModel: VoiceCollectionViewModel,
    settingsViewModel: SettingsViewModel,
    helpViewModel: HelpViewModel,
    knowledgeViewModel: KnowledgeViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val invitationState by viewModel.invitationState.collectAsState()
    val rootContext = LocalContext.current
    LaunchedEffect(uiState) {
        if (uiState !is AiFriendUiState.Ready) {
            GuardianServiceController.stop(rootContext)
            guardianViewModel.reset()
            taskViewModel.leave()
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val state = uiState) {
            is AiFriendUiState.Ready -> ReadyNavigation(
                invitationState = invitationState,
                rootViewModel = viewModel,
                contactManagementViewModel = contactManagementViewModel,
                aliasEnrollmentViewModel = aliasEnrollmentViewModel,
                safetyCommandEnrollmentViewModel = safetyCommandEnrollmentViewModel,
                taskHistoryDeletionViewModel = taskHistoryDeletionViewModel,
                accountClosureViewModel = accountClosureViewModel,
                taskViewModel = taskViewModel,
                taskDecisionEnrollmentViewModel = taskDecisionEnrollmentViewModel,
                recentTaskResultsViewModel = recentTaskResultsViewModel,
                guardianViewModel = guardianViewModel,
                wakeWordEnrollmentViewModel = wakeWordEnrollmentViewModel,
                voiceCollectionViewModel = voiceCollectionViewModel,
                settingsViewModel = settingsViewModel,
                helpViewModel = helpViewModel,
                knowledgeViewModel = knowledgeViewModel,
            )
            else -> if (BuildConfig.WECHAT_SAMPLE_CAPTURE_ENABLED) {
                WechatSampleCaptureRoute(settingsViewModel)
            } else {
                WelcomeStateContent(
                    state = state,
                    deviceFingerprint = viewModel.deviceFingerprint,
                    onWechatLogin = viewModel::startWechatLogin,
                    onLocalLogin = viewModel::loginForLocalDevelopment,
                    onGrantConsent = viewModel::grantBasicIdentityConsent,
                    onExit = viewModel::clearLocalSession,
                    onRetry = viewModel::restoreSession,
                    onRetryWipe = viewModel::retryAccountWipe,
                )
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun ReadyNavigation(
    invitationState: InvitationUiState,
    rootViewModel: AiFriendViewModel,
    contactManagementViewModel: ContactManagementViewModel,
    aliasEnrollmentViewModel: AliasEnrollmentViewModel,
    safetyCommandEnrollmentViewModel: SafetyCommandEnrollmentViewModel,
    taskHistoryDeletionViewModel: TaskHistoryDeletionViewModel,
    accountClosureViewModel: AccountClosureViewModel,
    taskViewModel: TaskViewModel,
    taskDecisionEnrollmentViewModel: TaskDecisionEnrollmentViewModel,
    recentTaskResultsViewModel: RecentTaskResultsViewModel,
    guardianViewModel: GuardianViewModel,
    wakeWordEnrollmentViewModel: WakeWordEnrollmentViewModel,
    voiceCollectionViewModel: VoiceCollectionViewModel,
    settingsViewModel: SettingsViewModel,
    helpViewModel: HelpViewModel,
    knowledgeViewModel: KnowledgeViewModel,
) {
    val navController = rememberNavController()
    val contactState by contactManagementViewModel.uiState.collectAsState()
    val aliasEnrollmentState by aliasEnrollmentViewModel.uiState.collectAsState()
    val safetyCommandEnrollmentState by safetyCommandEnrollmentViewModel.uiState.collectAsState()
    val wakeWordEnrollmentState by wakeWordEnrollmentViewModel.uiState.collectAsState()
    val taskDecisionEnrollmentState by taskDecisionEnrollmentViewModel.uiState.collectAsState()
    val guardianStatus by guardianViewModel.status.collectAsState()
    val capabilityStatus by settingsViewModel.capabilityStatus.collectAsState()
    val pendingGuardianHandoff by taskViewModel.pendingGuardianHandoff.collectAsState()
    val context = LocalContext.current
    val shareInvitation: (String) -> Unit = { shareUrl ->
        routeWechatInvitationShare(
            shareUrl = shareUrl,
            launchWechat = { request ->
                context.startActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        type = request.mimeType
                        setPackage(request.packageName)
                        putExtra(Intent.EXTRA_TEXT, request.text)
                    },
                )
                true
            },
            onFailure = rootViewModel::reportWechatInvitationShareFailure,
        )
    }
    LaunchedEffect(pendingGuardianHandoff?.sessionId) {
        val handoff = pendingGuardianHandoff ?: return@LaunchedEffect
        // 守护服务仅释放当前麦克风并保持前台存活；任务页结束后可原位恢复。
        val resourcesReleased = awaitGuardianTaskHandoff(status = guardianViewModel.status)
        if (!resourcesReleased) {
            taskViewModel.rejectGuardianSessionHandoff(
                sessionId = handoff.sessionId,
                message = "小友守护没有及时完成任务交接，本次任务已停止",
            )
            navController.navigate(TASK_ROUTE) { launchSingleTop = true }
            return@LaunchedEffect
        }
        taskViewModel.adoptGuardianSession(handoff.sessionId)
        navController.navigate(TASK_ROUTE) { launchSingleTop = true }
    }
    LaunchedEffect(Unit) {
        taskViewModel.wechatLaunchRequests.collect {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(WECHAT_PACKAGE_NAME)
            if (launchIntent == null) {
                taskViewModel.reportWechatLaunchFailed()
            } else {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                runCatching { context.startActivity(launchIntent) }
                    .onFailure { taskViewModel.reportWechatLaunchFailed() }
            }
        }
    }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val topLevelRoute = MainDestination.entries.any { it.route == currentRoute }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (topLevelRoute) {
                MainBottomNavigation(
                    selectedRoute = currentRoute ?: MainDestination.HOME.route,
                    onSelect = { destination ->
                        if (destination.route != currentRoute) {
                            navController.navigate(destination.route) {
                                popUpTo(MainDestination.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.HOME.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(MainDestination.HOME.route) {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, settingsViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            settingsViewModel.refreshCapabilityStatus()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(Unit) {
                    contactManagementViewModel.loadContacts()
                    settingsViewModel.refreshCapabilityStatus()
                }
                HomeScreen(
                    guardianStatus = guardianStatus,
                    guardianWakeReadiness = guardianViewModel.wakeReadiness,
                    capabilityStatus = capabilityStatus,
                    contactState = contactState,
                    demoEnabled = BuildConfig.MVP_DEMO_ENABLED,
                    onStartTask = {
                        taskViewModel.open()
                        navController.navigate(TASK_ROUTE)
                    },
                    onOpenDemoSetup = {
                        navController.navigate(CONTACTS_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onOpenContacts = {
                        navController.navigate(CONTACTS_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onGuardianPermissionDenied = guardianViewModel::onPermissionDenied,
                    onGuardianWakeUnavailable = guardianViewModel::onWakeUnavailable,
                )
            }
            composable(MainDestination.FAMILY.route) {
                LaunchedEffect(Unit) {
                    contactManagementViewModel.loadContacts()
                }
                FamilyHubScreen(
                    invitationState = invitationState,
                    contactState = contactState,
                    onOpenFamilySetup = { navController.navigate(FAMILY_SETUP_ROUTE) },
                    onOpenContacts = { navController.navigate(CONTACTS_ROUTE) },
                    onCreateInvitation = rootViewModel::createInvitation,
                    onRefreshInvitations = rootViewModel::refreshInvitations,
                    onRefreshFamily = {
                        rootViewModel.refreshInvitations()
                        contactManagementViewModel.refreshContacts()
                    },
                    onRevokeInvitation = rootViewModel::revokeInvitation,
                    onShareInvitation = shareInvitation,
                )
            }
            composable(MainDestination.PROFILE.route) {
                ProfileScreen(
                    onOpenKnowledge = { navController.navigate(KNOWLEDGE_ROUTE) { launchSingleTop = true } },
                    onOpenWakeWord = {
                        GuardianServiceController.stop(context)
                        guardianViewModel.reset()
                        wakeWordEnrollmentViewModel.open()
                        navController.navigate(WAKE_WORD_ENROLLMENT_ROUTE)
                    },
                    onOpenSafetyCommands = {
                        safetyCommandEnrollmentViewModel.open()
                        navController.navigate(SAFETY_COMMAND_ENROLLMENT_ROUTE)
                    },
                    onOpenVoiceCollection = {
                        voiceCollectionViewModel.open()
                        navController.navigate(VOICE_COLLECTION_ROUTE)
                    },
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    onOpenRecentTaskResults = {
                        navController.navigate(RECENT_TASK_RESULTS_ROUTE)
                    },
                    onOpenTaskHistoryDeletion = {
                        taskHistoryDeletionViewModel.open()
                        navController.navigate(TASK_HISTORY_DELETION_ROUTE)
                    },
                    onOpenAccountClosure = {
                        GuardianServiceController.stop(context)
                        guardianViewModel.reset()
                        taskViewModel.leave()
                        accountClosureViewModel.open()
                        navController.navigate(ACCOUNT_CLOSURE_ROUTE)
                    },
                    onExit = {
                        GuardianServiceController.stop(context)
                        guardianViewModel.reset()
                        rootViewModel.clearLocalSession()
                    },
                )
            }
            composable(FAMILY_SETUP_ROUTE) {
            FamilySetupScreen(
                invitationState = invitationState,
                onCreateInvitation = rootViewModel::createInvitation,
                onRefreshInvitations = rootViewModel::refreshInvitations,
                onShareInvitation = shareInvitation,
                onRevokeInvitation = rootViewModel::revokeInvitation,
                onOpenContacts = { navController.navigate(CONTACTS_ROUTE) },
                onOpenSafetyCommands = {
                    safetyCommandEnrollmentViewModel.open()
                    navController.navigate(SAFETY_COMMAND_ENROLLMENT_ROUTE)
                },
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(TASK_ROUTE) {
            TaskRoute(
                viewModel = taskViewModel,
                onBack = { navController.popBackStack() },
                onResumeGuardian = {
                    // TaskViewModel 已通知同一守护服务原位恢复。
                    navController.popBackStack()
                },
            )
        }
        composable(RECENT_TASK_RESULTS_ROUTE) {
            RecentTaskResultsRoute(
                viewModel = recentTaskResultsViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(CONTACTS_ROUTE) {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, contactManagementViewModel) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        contactManagementViewModel.refreshLocalVerification()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                contactManagementViewModel.loadContacts()
            }
            ContactManagementScreen(
                state = contactState,
                onBack = { navController.popBackStack() },
                onRefresh = contactManagementViewModel::refreshContacts,
                onManageAliases = { contact ->
                    aliasEnrollmentViewModel.open(contact)
                    navController.navigate(ALIAS_ENROLLMENT_ROUTE)
                },
                onRequestUnbind = contactManagementViewModel::requestUnbind,
                onContinueUnbind = contactManagementViewModel::continueUnbindConfirmation,
                onConfirmUnbind = contactManagementViewModel::confirmUnbind,
                onCancelUnbind = contactManagementViewModel::cancelUnbind,
                onDismissMessage = contactManagementViewModel::dismissMessage,
                onStartLocalVerification =
                    contactManagementViewModel::startLocalVerification,
                onOpenWechatForVerification = {
                    if (contactManagementViewModel.beginLocalVerificationObservation()) {
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(WECHAT_PACKAGE_NAME)
                        if (launchIntent == null) {
                            contactManagementViewModel
                                .failToOpenWechatForLocalVerification()
                        } else {
                            runCatching { context.startActivity(launchIntent) }
                                .onFailure {
                                    contactManagementViewModel
                                        .failToOpenWechatForLocalVerification()
                                }
                        }
                    }
                },
                onOpenAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onConfirmLocalVerification =
                    contactManagementViewModel::confirmLocalVerification,
                onCancelLocalVerification =
                    contactManagementViewModel::cancelLocalVerification,
                demoEnabled = BuildConfig.MVP_DEMO_ENABLED,
                onPrepareDemoContact = contactManagementViewModel::prepareDemoContact,
                onRefreshDemoReadiness = contactManagementViewModel::refreshDemoReadiness,
                onOpenSafetyCommands = {
                    safetyCommandEnrollmentViewModel.open()
                    navController.navigate(SAFETY_COMMAND_ENROLLMENT_ROUTE)
                },
                onStartTask = {
                    taskViewModel.open()
                    navController.navigate(TASK_ROUTE)
                },
            )
        }
        composable(ALIAS_ENROLLMENT_ROUTE) {
            LaunchedEffect(aliasEnrollmentState.contactId) {
                if (aliasEnrollmentState.contactId == null) {
                    navController.popBackStack()
                }
            }
            val leaveAliasEnrollment = {
                aliasEnrollmentViewModel.leave()
                navController.popBackStack()
                contactManagementViewModel.refreshContacts()
                Unit
            }
            AliasEnrollmentRoute(
                state = aliasEnrollmentState,
                onBack = leaveAliasEnrollment,
                onDisplayTextChanged = aliasEnrollmentViewModel::updateDisplayText,
                onGrantConsent = aliasEnrollmentViewModel::grantVoiceTemplateConsent,
                onStartRecording = aliasEnrollmentViewModel::startRecording,
                onFinishRecording = aliasEnrollmentViewModel::finishRecording,
                onPermissionDenied = aliasEnrollmentViewModel::onMicrophonePermissionDenied,
                onPlay = aliasEnrollmentViewModel::play,
                onRetake = aliasEnrollmentViewModel::retake,
                onConfirmAndSubmit = aliasEnrollmentViewModel::confirmAndSubmit,
                onContinueAfterCompletion = aliasEnrollmentViewModel::continueAfterCompletion,
                onRequestAliasDeletion = aliasEnrollmentViewModel::requestAliasDeletion,
                onCancelAliasDeletion = aliasEnrollmentViewModel::cancelAliasDeletion,
                onConfirmAliasDeletion = aliasEnrollmentViewModel::confirmAliasDeletion,
                onDismissError = aliasEnrollmentViewModel::dismissError,
            )
        }
        composable(SAFETY_COMMAND_ENROLLMENT_ROUTE) {
            val leaveSafetyCommands = {
                safetyCommandEnrollmentViewModel.leave()
                navController.popBackStack()
                contactManagementViewModel.refreshDemoReadiness()
                Unit
            }
            SafetyCommandEnrollmentRoute(
                state = safetyCommandEnrollmentState,
                onBack = leaveSafetyCommands,
                onGrantConsent = safetyCommandEnrollmentViewModel::grantVoiceTemplateConsent,
                onStartFullReplacement = safetyCommandEnrollmentViewModel::startFullReplacement,
                onSelectCurrentPhrase = safetyCommandEnrollmentViewModel::selectCurrentPhrase,
                onStartRecording = safetyCommandEnrollmentViewModel::startRecording,
                onFinishRecording = safetyCommandEnrollmentViewModel::finishRecording,
                onPermissionDenied = safetyCommandEnrollmentViewModel::onMicrophonePermissionDenied,
                onPlay = safetyCommandEnrollmentViewModel::play,
                onRetake = safetyCommandEnrollmentViewModel::retake,
                onConfirmCurrentCommand = safetyCommandEnrollmentViewModel::confirmCurrentCommand,
                onRedoCommand = safetyCommandEnrollmentViewModel::redoCommand,
                onConfirmAndSubmitAll = safetyCommandEnrollmentViewModel::confirmAndSubmitAll,
                onOpenTaskDecisionEnrollment = {
                    taskDecisionEnrollmentViewModel.open()
                    navController.navigate(TASK_DECISION_ENROLLMENT_ROUTE)
                },
                onDismissError = safetyCommandEnrollmentViewModel::dismissError,
            )
        }
        composable(TASK_DECISION_ENROLLMENT_ROUTE) {
            TaskDecisionEnrollmentRoute(
                state = taskDecisionEnrollmentState,
                onBack = {
                    taskDecisionEnrollmentViewModel.leave()
                    navController.popBackStack()
                },
                onStartRecording = taskDecisionEnrollmentViewModel::startRecording,
                onFinishRecording = taskDecisionEnrollmentViewModel::finishRecording,
                onPermissionDenied =
                    taskDecisionEnrollmentViewModel::onMicrophonePermissionDenied,
                onConfirmSave = taskDecisionEnrollmentViewModel::confirmSave,
                onRestart = taskDecisionEnrollmentViewModel::restart,
                onDismissError = taskDecisionEnrollmentViewModel::dismissError,
            )
        }
        composable(WAKE_WORD_ENROLLMENT_ROUTE) {
            WakeWordEnrollmentRoute(
                state = wakeWordEnrollmentState,
                onBack = {
                    wakeWordEnrollmentViewModel.leave()
                    navController.popBackStack()
                },
                onStartRecording = wakeWordEnrollmentViewModel::startRecording,
                onFinishRecording = wakeWordEnrollmentViewModel::finishRecording,
                onPermissionDenied = wakeWordEnrollmentViewModel::onMicrophonePermissionDenied,
                onPlay = wakeWordEnrollmentViewModel::play,
                onRetake = wakeWordEnrollmentViewModel::retake,
                onConfirmSave = wakeWordEnrollmentViewModel::confirmSave,
                onRecordAgain = wakeWordEnrollmentViewModel::recordAgain,
                onDismissError = wakeWordEnrollmentViewModel::dismissError,
            )
        }
        composable(VOICE_COLLECTION_ROUTE) {
            VoiceCollectionRoute(
                viewModel = voiceCollectionViewModel,
                onBack = {
                    voiceCollectionViewModel.leave()
                    navController.popBackStack()
                },
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsRoute(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenHelp = { navController.navigate(HELP_ROUTE) },
                onOpenTaskHistoryDeletion = {
                    taskHistoryDeletionViewModel.open()
                    navController.navigate(TASK_HISTORY_DELETION_ROUTE)
                },
                onOpenAccountClosure = {
                    GuardianServiceController.stop(context)
                    guardianViewModel.reset()
                    taskViewModel.leave()
                    accountClosureViewModel.open()
                    navController.navigate(ACCOUNT_CLOSURE_ROUTE)
                },
            )
        }
        composable(HELP_ROUTE) {
            HelpRoute(
                viewModel = helpViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(KNOWLEDGE_ROUTE) {
            KnowledgeRoute(knowledgeViewModel, onBack = { navController.popBackStack() })
        }
        composable(TASK_HISTORY_DELETION_ROUTE) {
            TaskHistoryDeletionRoute(
                viewModel = taskHistoryDeletionViewModel,
                onAccepted = taskViewModel::leave,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ACCOUNT_CLOSURE_ROUTE) {
            AccountClosureRoute(
                viewModel = accountClosureViewModel,
                onAccepted = rootViewModel::onAccountClosureAccepted,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

}

private const val FAMILY_SETUP_ROUTE = "family-setup"
private const val TASK_ROUTE = "task"
private const val RECENT_TASK_RESULTS_ROUTE = "tasks/recent-results"
private const val CONTACTS_ROUTE = "contacts"
private const val ALIAS_ENROLLMENT_ROUTE = "contacts/alias-enrollment"
private const val SAFETY_COMMAND_ENROLLMENT_ROUTE = "voice/safety-command-enrollment"
private const val WAKE_WORD_ENROLLMENT_ROUTE = "voice/wake-word-enrollment"
private const val TASK_DECISION_ENROLLMENT_ROUTE = "voice/task-decision-enrollment"
private const val VOICE_COLLECTION_ROUTE = "voice/test-collection"
private const val SETTINGS_ROUTE = "settings"
private const val HELP_ROUTE = "help"
private const val KNOWLEDGE_ROUTE = "assistant/knowledge"
private const val TASK_HISTORY_DELETION_ROUTE = "privacy/task-history-deletion"
private const val ACCOUNT_CLOSURE_ROUTE = "privacy/account-closure"
