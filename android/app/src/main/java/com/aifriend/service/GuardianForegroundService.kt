package com.aifriend.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aifriend.R
import com.aifriend.app.MainActivity
import com.aifriend.core.audio.Pcm16RingBuffer
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.feature.guardian.GuardianAudioStream
import com.aifriend.feature.guardian.GuardianAudioStreamException
import com.aifriend.feature.guardian.GuardianAcknowledgement
import com.aifriend.feature.guardian.GuardianCaptureBoundary
import com.aifriend.feature.guardian.GuardianEvent
import com.aifriend.feature.guardian.GuardianMode
import com.aifriend.feature.guardian.GuardianRuntimeStore
import com.aifriend.feature.guardian.GuardianStatus
import com.aifriend.feature.guardian.GuardianTaskSubmissionException
import com.aifriend.feature.guardian.GuardianTaskCapture
import com.aifriend.feature.guardian.GuardianTaskSubmission
import com.aifriend.feature.guardian.GuardianWakeReadiness
import com.aifriend.feature.guardian.GuardianWakeWordDetector
import com.aifriend.feature.guardian.GuardianWechatCallAudioCoordinator
import com.aifriend.feature.guardian.GuardianQuestionAudioCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 小友守护前台服务。
 *
 * 只接受可见页面或持续通知发出的明确命令，使用纯内存 PCM 环形缓冲执行本地
 * 双唤醒。进程重建、任务移除、权限撤回或录音异常后不会自动恢复。
 *
 * @author codex
 * @since 2026-08-14
 */
@AndroidEntryPoint
class GuardianForegroundService : Service() {

    @Inject lateinit var audioStream: GuardianAudioStream
    @Inject lateinit var runtimeStore: GuardianRuntimeStore
    @Inject lateinit var wakeWordDetector: GuardianWakeWordDetector
    @Inject lateinit var acknowledgement: GuardianAcknowledgement
    @Inject lateinit var taskSubmission: GuardianTaskSubmission
    @Inject lateinit var wechatCallAudioCoordinator: GuardianWechatCallAudioCoordinator
    @Inject lateinit var questionAudioCoordinator: GuardianQuestionAudioCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ringBuffer = Pcm16RingBuffer(WavPcmCodec.SAMPLE_RATE * RING_BUFFER_SECONDS)
    private val taskCapture = GuardianTaskCapture()
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var keyguardManager: KeyguardManager
    private var foregroundStarted = false
    private var interruptionMonitorJob: Job? = null
    private var taskWorkJob: Job? = null
    private var wechatCallPreparationTimeoutJob: Job? = null
    private val wechatCallAudioOwner = Any()
    private val questionAudioOwner = Any()
    @Volatile private var serviceDestroyed = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        wechatCallAudioCoordinator.register(
            owner = wechatCallAudioOwner,
            releaseBeforeCall = ::releaseBeforeWechatCall,
            resumeIfIdle = ::resumeAfterWechatCallFailure,
        )
        createNotificationChannel()
        questionAudioCoordinator.register(questionAudioOwner, ::pauseForQuestion, ::resumeAfterQuestion)
        serviceScope.launch {
            runtimeStore.status.collectLatest { status ->
                if (foregroundStarted) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> serviceScope.launch { startGuardian(startId) }
            ACTION_STOP -> {
                runtimeStore.dispatch(GuardianEvent.DisableRequested)
                serviceScope.launch { stopGuardian(startId, reportDisabled = true) }
            }
            ACTION_CANCEL_TASK -> serviceScope.launch {
                taskWorkJob?.cancelAndJoin()
                stopCurrentTask("本次任务已取消，正在继续等待唤醒")
            }
            else -> serviceScope.launch { stopGuardian(startId, reportDisabled = true) }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runtimeStore.dispatch(GuardianEvent.DisableRequested)
        serviceScope.launch { stopGuardian(null, reportDisabled = true) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        serviceScope.cancel()
        questionAudioCoordinator.unregister(questionAudioOwner)
        runtimeStore.onServiceDestroyed()
        wechatCallAudioCoordinator.unregister(wechatCallAudioOwner)
        wechatCallPreparationTimeoutJob?.cancel()
        interruptionMonitorJob?.cancel()
        taskWorkJob?.cancel()
        runBlocking(Dispatchers.IO) { audioStream.stop() }
        ringBuffer.clear()
        taskCapture.clear()
        wakeWordDetector.close()
        acknowledgement.close()
        foregroundStarted = false
        super.onDestroy()
    }

    private suspend fun startGuardian(startId: Int) {
        if (runtimeStore.status.value.active) return
        runtimeStore.dispatch(GuardianEvent.EnableRequested)
        if (!requiredPermissionsGranted()) {
            runtimeStore.dispatch(GuardianEvent.PermissionRevoked)
            stopGuardian(startId, reportDisabled = false)
            return
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(runtimeStore.status.value))
            foregroundStarted = true
        } catch (_: SecurityException) {
            failAndStop("系统不允许使用麦克风前台服务，小友守护没有开启", startId)
            return
        }
        val started = questionAudioCoordinator.guardianOperation(questionAudioOwner, wait = true) {
            prepareAndStartGuardian(startId)
        }
        if (!started && !serviceDestroyed && runtimeStore.status.value.mode == GuardianMode.STARTING) {
            failAndStop("问答语音资源尚未确认释放，暂时不能开启守护", startId)
        }
    }

    private suspend fun prepareAndStartGuardian(startId: Int) {
        if (serviceDestroyed || runtimeStore.status.value.mode != GuardianMode.STARTING) return
        val readiness = wakeWordDetector.prepare()
        if (readiness != GuardianWakeReadiness.READY) {
            failAndStop(readiness.userMessage, startId)
            return
        }
        if (!acknowledgement.prepare()) {
            failAndStop("手机没有可用的离线中文播报声音，小友守护没有开启", startId)
            return
        }
        try {
            if (serviceDestroyed || runtimeStore.status.value.mode != GuardianMode.STARTING) return
            if (!requiredPermissionsGranted()) {
                runtimeStore.dispatch(GuardianEvent.PermissionRevoked)
                stopGuardian(startId, reportDisabled = false)
                return
            }
            ringBuffer.clear()
            wakeWordDetector.reset()
            startAudioStreamIfStillEnabled()
            runtimeStore.dispatch(GuardianEvent.CaptureStarted)
            startInterruptionMonitor()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: GuardianAudioStreamException) {
            failAndStop(exception.failure.userMessage, startId)
        } catch (_: Exception) {
            failAndStop("当前设备无法启动小友守护", startId)
        }
    }

    private fun onAudioChunk(samples: ShortArray, count: Int, elapsedRealtimeMs: Long) {
        when (runtimeStore.status.value.mode) {
            GuardianMode.SLEEPING -> {
                ringBuffer.append(samples, count)
                val verifiedHits = wakeWordDetector.detect(samples, count, elapsedRealtimeMs)
                if (verifiedHits <= 0) return
                var after = runtimeStore.status.value.mode
                for (index in 0 until verifiedHits.coerceAtMost(2)) {
                    after = runtimeStore.dispatch(
                        GuardianEvent.WakeWordDetected(elapsedRealtimeMs + index),
                    ).mode
                    if (after == GuardianMode.AWAKE_LISTENING) break
                }
                if (after == GuardianMode.AWAKE_LISTENING) {
                    ringBuffer.clear()
                    taskWorkJob?.cancel()
                    taskWorkJob = serviceScope.launch { beginTaskCapture() }
                }
            }
            GuardianMode.AWAKE_LISTENING -> if (taskCapture.isCapturing()) {
                when (taskCapture.append(samples, count)) {
                    GuardianCaptureBoundary.CONTINUE -> Unit
                    GuardianCaptureBoundary.NO_SPEECH_TIMEOUT -> serviceScope.launch {
                        taskCapture.clear()
                        ringBuffer.clear()
                        wakeWordDetector.reset()
                        runtimeStore.dispatch(GuardianEvent.ListeningTimedOut)
                    }
                    GuardianCaptureBoundary.UTTERANCE_COMPLETE,
                    GuardianCaptureBoundary.MAXIMUM_REACHED,
                    -> {
                        taskWorkJob?.cancel()
                        taskWorkJob = serviceScope.launch { finishAndSubmitTask() }
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun beginTaskCapture() {
        if (keyguardManager.isDeviceLocked) {
            stopCurrentTask("手机已锁定，请解锁后重新说两次小友")
            return
        }
        if (!acknowledgement.speak()) {
            failAndStop("离线应答播报失败，小友守护已停止", null)
            return
        }
        if (runtimeStore.status.value.mode != GuardianMode.AWAKE_LISTENING ||
            keyguardManager.isDeviceLocked
        ) {
            stopCurrentTask("本次任务已停止，请解锁后重新说两次小友")
            return
        }
        // 播报期间不采集任务正文，结束后再次清空，确保唤醒词、环境音和本机播报不进入上传音频。
        ringBuffer.clear()
        taskCapture.start()
    }

    private suspend fun finishAndSubmitTask() {
        audioStream.stop()
        val captured = runCatching { taskCapture.finish() }.getOrElse { exception ->
            taskCapture.clear()
            runtimeStore.dispatch(
                GuardianEvent.TaskCaptureStopped(
                    exception.toChineseUserMessage("没有录到有效任务内容"),
                ),
            )
            restartSleepingCapture()
            return
        }
        runtimeStore.dispatch(GuardianEvent.ProcessingStarted)
        var handedOff = false
        try {
            // 任务自由识别会复用同一个 Vosk 模型；先释放唤醒模型，避免低内存设备同时加载两份。
            wakeWordDetector.close()
            taskSubmission.submit(captured)
            runtimeStore.dispatch(GuardianEvent.TaskHandedOff)
            handedOff = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val stage = (exception as? GuardianTaskSubmissionException)?.stage?.name
                ?: "UNKNOWN"
            Log.w(GUARDIAN_TASK_TAG, "Guardian task submission failed at stage=" + stage)
            runtimeStore.dispatch(
                GuardianEvent.TaskCaptureStopped(
                    exception.toChineseUserMessage("本次任务没有创建，录音已清除"),
                ),
            )
        } finally {
            captured.clear()
            ringBuffer.clear()
            wakeWordDetector.reset()
        }
        if (!handedOff) restartSleepingCapture()
    }

    private suspend fun stopCurrentTask(message: String) {
        taskCapture.clear()
        ringBuffer.clear()
        wakeWordDetector.reset()
        acknowledgement.close()
        if (runtimeStore.status.value.mode in setOf(
                GuardianMode.AWAKE_LISTENING,
                GuardianMode.PROCESSING,
            )
        ) {
            runtimeStore.dispatch(GuardianEvent.TaskCaptureStopped(message))
        }
        if (!acknowledgement.prepare()) {
            failAndStop("手机的离线中文播报已不可用，小友守护已停止", null)
            return
        }
        restartSleepingCapture()
    }

    private suspend fun restartSleepingCapture() {
        questionAudioCoordinator.guardianOperation(questionAudioOwner) { restartSleepingCaptureUnlocked() }
    }

    private suspend fun restartSleepingCaptureUnlocked() {
        if (serviceDestroyed || runtimeStore.status.value.mode != GuardianMode.SLEEPING ||
            keyguardManager.isDeviceLocked ||
            audioManager.mode != AudioManager.MODE_NORMAL ||
            !requiredPermissionsGranted()
        ) {
            return
        }
        if (wakeWordDetector.prepare() != GuardianWakeReadiness.READY) {
            failAndStop("本地双唤醒模型不可用，小友守护已停止", null)
            return
        }
        try {
            startAudioStreamIfStillEnabled()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: GuardianAudioStreamException) {
            failAndStop(exception.failure.userMessage, null)
        } catch (_: Exception) {
            failAndStop("无法恢复本地唤醒，小友守护已停止", null)
        }
    }

    private fun onAudioFailure(failure: com.aifriend.feature.guardian.GuardianAudioFailure) {
        serviceScope.launch { failAndStop(failure.userMessage, null) }
    }

    private fun startInterruptionMonitor() {
        interruptionMonitorJob?.cancel()
        interruptionMonitorJob = serviceScope.launch {
            var busy = false
            var locked = keyguardManager.isDeviceLocked
            while (true) {
                val currentBusy = audioManager.mode != AudioManager.MODE_NORMAL
                val currentLocked = keyguardManager.isDeviceLocked
                if (!requiredPermissionsGranted()) {
                    failAndStop("麦克风或通知权限已关闭，小友守护已停止", null)
                    return@launch
                }
                if (currentLocked && runtimeStore.status.value.mode in setOf(
                        GuardianMode.AWAKE_LISTENING,
                        GuardianMode.PROCESSING,
                    )
                ) {
                    taskWorkJob?.cancelAndJoin()
                    stopCurrentTask("手机已锁定，本次任务已清除，解锁后请重新说")
                }
                if (!currentLocked && locked && runtimeStore.status.value.mode == GuardianMode.SLEEPING) {
                    restartSleepingCapture()
                }
                locked = currentLocked
                if (runtimeStore.status.value.mode == GuardianMode.QUESTION_PAUSED) {
                    busy = currentBusy
                    delay(AUDIO_MODE_POLL_MS)
                    continue
                }
                if (currentBusy && !busy) {
                    busy = true
                    wechatCallPreparationTimeoutJob?.cancel()
                    wechatCallPreparationTimeoutJob = null
                    taskWorkJob?.cancelAndJoin()
                    audioStream.stop()
                    ringBuffer.clear()
                    taskCapture.clear()
                    acknowledgement.close()
                    wakeWordDetector.reset()
                    runtimeStore.dispatch(GuardianEvent.AudioBecameBusy)
                } else if (!currentBusy && busy) {
                    busy = false
                    resumeWechatCallIfIdle()
                }
                delay(AUDIO_MODE_POLL_MS)
            }
        }
    }

    /** 最终通话类型点击前只释放麦克风；实际通信音频出现前仍保持任务交接状态。 */
    private suspend fun releaseBeforeWechatCall() {
        taskWorkJob?.cancelAndJoin()
        audioStream.stop()
        ringBuffer.clear()
        taskCapture.clear()
        acknowledgement.close()
        wakeWordDetector.reset()
        wechatCallPreparationTimeoutJob?.cancel()
        wechatCallPreparationTimeoutJob = serviceScope.launch {
            delay(WECHAT_CALL_AUDIO_HANDOFF_TIMEOUT_MILLIS)
            resumeWechatCallIfIdle()
        }
    }

    private suspend fun resumeAfterWechatCallFailure() {
        wechatCallPreparationTimeoutJob?.cancel()
        wechatCallPreparationTimeoutJob = null
        resumeWechatCallIfIdle()
    }

    private suspend fun resumeWechatCallIfIdle() {
        questionAudioCoordinator.guardianOperation(questionAudioOwner) { resumeWechatCallIfIdleUnlocked() }
    }

    private suspend fun resumeWechatCallIfIdleUnlocked() {
        if (serviceDestroyed || keyguardManager.isDeviceLocked ||
            audioManager.mode != AudioManager.MODE_NORMAL ||
            !requiredPermissionsGranted()
        ) {
            return
        }
        if (runtimeStore.status.value.mode == GuardianMode.TASK_HANDOFF) {
            try {
                if (!acknowledgement.prepare()) {
                    failAndStop("手机的离线中文播报已不可用，小友守护已停止", null)
                    return
                }
                runtimeStore.dispatch(GuardianEvent.ForegroundTaskFinished)
                startAudioStreamIfStillEnabled()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: GuardianAudioStreamException) {
                failAndStop(exception.failure.userMessage, null)
            } catch (_: Exception) {
                failAndStop("无法恢复本地唤醒，小友守护已停止", null)
            }
            return
        }
        if (runtimeStore.status.value.mode == GuardianMode.SLEEPING) {
            // 释放操作可能在停止录音后、切换 WECHAT_BUSY 前被超时取消；start 本身幂等。
            restartSleepingCaptureUnlocked()
            return
        }
        if (runtimeStore.status.value.mode != GuardianMode.WECHAT_BUSY) return
        try {
            if (!acknowledgement.prepare()) {
                failAndStop("手机的离线中文播报已不可用，小友守护已停止", null)
                return
            }
            startAudioStreamIfStillEnabled()
            runtimeStore.dispatch(GuardianEvent.AudioBecameAvailable)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: GuardianAudioStreamException) {
            failAndStop(exception.failure.userMessage, null)
        } catch (_: Exception) {
            failAndStop("无法恢复本地唤醒，小友守护已停止", null)
        }
    }

    /** 由协调器互斥保护；显式关闭在start前后发生都不能留下新录音。 */
    private suspend fun startAudioStreamIfStillEnabled() {
        if (serviceDestroyed || !runtimeStore.status.value.active || runtimeStore.status.value.mode == GuardianMode.QUESTION_PAUSED) return
        audioStream.start(::onAudioChunk, ::onAudioFailure)
        if (serviceDestroyed || !runtimeStore.status.value.active || runtimeStore.status.value.mode == GuardianMode.QUESTION_PAUSED) audioStream.stop()
    }

    private suspend fun pauseForQuestion(): Boolean = withContext(NonCancellable) {
        if (serviceDestroyed) return@withContext false
        if (!foregroundStarted && !runtimeStore.status.value.active) return@withContext true
        if (runtimeStore.dispatch(GuardianEvent.QuestionPauseRequested).mode != GuardianMode.QUESTION_PAUSED) return@withContext false
        try {
            audioStream.stop()
            ringBuffer.clear()
            taskCapture.clear()
            wakeWordDetector.close()
            acknowledgement.close()
            !serviceDestroyed && foregroundStarted && runtimeStore.status.value.mode == GuardianMode.QUESTION_PAUSED
        } catch (_: Exception) {
            if (!serviceDestroyed) {
                runtimeStore.dispatch(GuardianEvent.Failed("守护语音资源释放失败，问答未开始录音"))
                serviceScope.launch { stopGuardian(null, reportDisabled = false) }
            }
            false
        } catch (_: LinkageError) {
            if (!serviceDestroyed) {
                runtimeStore.dispatch(GuardianEvent.Failed("守护语音组件不可用，问答未开始录音"))
                serviceScope.launch { stopGuardian(null, reportDisabled = false) }
            }
            false
        }
    }

    /** 仅恢复仍存活、仍处于本次问答暂停态的服务，不启动新服务或覆盖用户关闭。 */
    private fun resumeAfterQuestion() {
        if (serviceDestroyed || !foregroundStarted || runtimeStore.status.value.mode != GuardianMode.QUESTION_PAUSED) return
        runtimeStore.dispatch(GuardianEvent.QuestionReleased)
        serviceScope.launch { restartSleepingCapture() }
    }

    private suspend fun failAndStop(message: String, startId: Int?) {
        runtimeStore.dispatch(GuardianEvent.Failed(message))
        stopGuardian(startId, reportDisabled = false)
    }

    private suspend fun stopGuardian(startId: Int?, reportDisabled: Boolean) {
        if (reportDisabled) runtimeStore.dispatch(GuardianEvent.DisableRequested)
        wechatCallPreparationTimeoutJob?.cancel()
        wechatCallPreparationTimeoutJob = null
        interruptionMonitorJob?.cancel()
        interruptionMonitorJob = null
        taskWorkJob?.cancel()
        taskWorkJob = null
        audioStream.stop()
        ringBuffer.clear()
        taskCapture.clear()
        wakeWordDetector.reset()
        acknowledgement.close()
        if (reportDisabled) runtimeStore.dispatch(GuardianEvent.DisableRequested)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun requiredPermissionsGranted(): Boolean {
        val microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return microphoneGranted && notificationGranted && notificationManager.areNotificationsEnabled()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.guardian_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.guardian_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: GuardianStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.guardian_notification_title))
            .setContentText(status.message)
            .setContentIntent(openIntent)
            .setOngoing(status.active)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.guardian_notification_manage), openIntent)
        if (status.mode == GuardianMode.AWAKE_LISTENING || status.mode == GuardianMode.PROCESSING) {
            val cancelIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, GuardianForegroundService::class.java).setAction(ACTION_CANCEL_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.guardian_notification_cancel_task), cancelIntent)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.aifriend.action.START_GUARDIAN"
        const val ACTION_STOP = "com.aifriend.action.STOP_GUARDIAN"
        const val ACTION_CANCEL_TASK = "com.aifriend.action.CANCEL_GUARDIAN_TASK"
        private const val GUARDIAN_TASK_TAG = "AiFriendGuardianTask"
        private const val NOTIFICATION_CHANNEL_ID = "guardian_microphone_v1"
        private const val NOTIFICATION_ID = 1_001
        private const val RING_BUFFER_SECONDS = 5
        private const val AUDIO_MODE_POLL_MS = 500L
        private const val WECHAT_CALL_AUDIO_HANDOFF_TIMEOUT_MILLIS = 5_000L
    }
}
