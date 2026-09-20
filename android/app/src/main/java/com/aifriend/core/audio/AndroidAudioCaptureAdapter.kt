package com.aifriend.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 使用 Android [AudioRecord] 录制内部基线 PCM 的适配器。
 *
 * <p>录音期间只把原始 PCM 写入应用私有缓存目录，停止后读入当前内存并
 * 删除临时文件。不使用外部存储，不把文件路径暴露给业务层。
 *
 * @author codex
 * @since 2026-08-12
 */
@Singleton
class AndroidAudioCaptureAdapter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AudioCapturePort {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val mutableState = MutableStateFlow(AudioCaptureState.STOPPED)
    private var activeSession: CaptureSession? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeFocusToken: Any? = null

    override val state: StateFlow<AudioCaptureState> = mutableState.asStateFlow()

    /** 新页面使用独立能力令牌；旧接口仍使用null归属，不能清理租约录音。 */
    fun newOwnedCapture(): AudioCapturePort = OwnedAudioCapture(object : OwnedAudioCaptureDriver {
        override suspend fun startOwned(owner: Any, maxDurationMs: Int, automaticEndpoint: Boolean) =
            withContext(Dispatchers.IO) { startInternal(maxDurationMs, automaticEndpoint, owner) }
        override suspend fun awaitOwned(owner: Any) = awaitInternal(owner)
        override suspend fun stopOwned(owner: Any) = withContext(Dispatchers.IO) { stopInternal(owner) }
        override suspend fun cancelOwned(owner: Any) = withContext(Dispatchers.IO) { cancelInternal(owner) }
    })

    init {
        adapterScope.launch {
            sessionMutex.withLock {
                scrubStaleTemporaryFiles()
            }
        }
    }

    override suspend fun start(maxDurationMs: Int) {
        startInternal(maxDurationMs, automaticEndpoint = false)
    }

    override suspend fun startUtterance(maxDurationMs: Int) {
        startInternal(maxDurationMs, automaticEndpoint = true)
    }

    override suspend fun awaitSpeechEndpoint(): SpeechEndpointBoundary = awaitInternal(null)

    private suspend fun awaitInternal(owner: Any?): SpeechEndpointBoundary {
        val endpoint = sessionMutex.withLock { activeSession?.takeIf { ownsAudioSession(owner, it.owner) }?.speechEndpoint }
        return endpoint?.await() ?: SpeechEndpointBoundary.UNSUPPORTED
    }

    private suspend fun startInternal(maxDurationMs: Int, automaticEndpoint: Boolean, owner: Any? = null) {
        require(maxDurationMs in MIN_DURATION_MS..MAX_DURATION_MS) { "录音最长时间无效" }
        sessionMutex.withLock {
            if (activeSession != null) {
                throw AudioCaptureException(AudioCaptureFailure.ALREADY_RECORDING, "当前已有录音任务")
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                mutableState.value = AudioCaptureState.FAILED
                throw AudioCaptureException(
                    AudioCaptureFailure.PERMISSION_DENIED,
                    "麦克风权限未开启",
                )
            }
            mutableState.value = AudioCaptureState.STARTING
            val minimumBufferSize = AudioRecord.getMinBufferSize(
                WavPcmCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBufferSize <= 0) {
                mutableState.value = AudioCaptureState.FAILED
                throw AudioCaptureException(
                    AudioCaptureFailure.DEVICE_UNAVAILABLE,
                    "当前设备无法启动麦克风",
                )
            }
            val bufferSize = maxOf(minimumBufferSize, MIN_BUFFER_BYTES).let { size ->
                if (size % 2 == 0) size else size + 1
            }
            val temporaryDirectory = File(context.cacheDir, PRIVATE_AUDIO_DIRECTORY)
            if (!temporaryDirectory.exists() && !temporaryDirectory.mkdirs()) {
                mutableState.value = AudioCaptureState.FAILED
                throw AudioCaptureException(
                    AudioCaptureFailure.STORAGE_UNAVAILABLE,
                    "无法创建私有录音缓存",
                )
            }
            if (!scrubStaleTemporaryFiles()) {
                mutableState.value = AudioCaptureState.FAILED
                throw AudioCaptureException(
                    AudioCaptureFailure.STORAGE_UNAVAILABLE,
                    "无法清理上次中断的录音",
                )
            }
            val temporaryFile = withContext(Dispatchers.IO) {
                File.createTempFile(TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX, temporaryDirectory)
            }
            var recorder: AudioRecord? = null
            try {
                requestAudioFocus(owner)
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(WavPcmCodec.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioCaptureException(
                        AudioCaptureFailure.DEVICE_UNAVAILABLE,
                        "当前设备无法初始化麦克风",
                    )
                }
                recorder.startRecording()
                val session = CaptureSession(
                    owner = owner,
                    recorder = recorder,
                    temporaryFile = temporaryFile,
                    bufferSize = bufferSize,
                    maximumPcmBytes = maxDurationMs.toLong() *
                        WavPcmCodec.SAMPLE_RATE * BYTES_PER_SAMPLE / 1_000L,
                    endpointDetector = if (automaticEndpoint) {
                        PcmSpeechEndpointDetector(maximumDurationMs = maxDurationMs)
                    } else {
                        null
                    },
                    speechEndpoint = if (automaticEndpoint) CompletableDeferred() else null,
                )
                activeSession = session
                session.captureJob = adapterScope.launch { capturePcm(session) }
                mutableState.value = AudioCaptureState.CAPTURING
            } catch (exception: Exception) {
                runCatching { recorder?.release() }
                abandonAudioFocus()
                withContext(Dispatchers.IO) { deletePrivateTemporaryFile(temporaryFile) }
                mutableState.value = AudioCaptureState.FAILED
                if (exception is AudioCaptureException) throw exception
                if (exception is SecurityException) {
                    throw AudioCaptureException(
                        AudioCaptureFailure.PERMISSION_DENIED,
                        "麦克风权限已关闭",
                        exception,
                    )
                }
                throw AudioCaptureException(
                    AudioCaptureFailure.DEVICE_UNAVAILABLE,
                    "启动录音失败",
                    exception,
                )
            }
        }
    }

    override suspend fun stop(): CapturedAudio = stopInternal(null)

    private suspend fun stopInternal(owner: Any?): CapturedAudio = sessionMutex.withLock {
        val session = activeSession?.takeIf { ownsAudioSession(owner, it.owner) }
            ?: throw AudioCaptureException(
                AudioCaptureFailure.NO_ACTIVE_RECORDING,
                "当前没有正在录制的内容",
            )
        mutableState.value = AudioCaptureState.STOPPING
        session.active.set(false)
        runCatching { session.recorder.stop() }
        session.completed.await()
        session.captureJob?.join()
        activeSession = null
        runCatching { session.recorder.release() }
        abandonAudioFocus()
        var temporaryFileCleared = false
        val pcmBytes = withContext(Dispatchers.IO) {
            try {
                session.temporaryFile.readBytes()
            } finally {
                temporaryFileCleared = deletePrivateTemporaryFile(session.temporaryFile)
            }
        }
        if (!temporaryFileCleared) {
            pcmBytes.fill(0)
            mutableState.value = AudioCaptureState.FAILED
            throw AudioCaptureException(
                AudioCaptureFailure.STORAGE_UNAVAILABLE,
                "无法清理录音临时文件",
            )
        }
        session.failure?.let { failure ->
            pcmBytes.fill(0)
            mutableState.value = AudioCaptureState.FAILED
            throw AudioCaptureException(
                AudioCaptureFailure.READ_FAILED,
                "录音过程被中断",
                failure,
            )
        }
        if (pcmBytes.isEmpty() || pcmBytes.size % 2 != 0) {
            pcmBytes.fill(0)
            mutableState.value = AudioCaptureState.FAILED
            throw AudioCaptureException(
                AudioCaptureFailure.READ_FAILED,
                "没有录制到有效声音",
            )
        }
        val wavBytes = try {
            WavPcmCodec.encodeMono16(pcmBytes)
        } finally {
            pcmBytes.fill(0)
        }
        mutableState.value = AudioCaptureState.STOPPED
        CapturedAudio(
            wavBytes = wavBytes,
            durationMs = ((wavBytes.size - WavPcmCodec.HEADER_SIZE).toLong() * 1_000L /
                (WavPcmCodec.SAMPLE_RATE * BYTES_PER_SAMPLE)).toInt(),
        )
    }

    override suspend fun cancel(): Unit = cancelInternal(null)

    private suspend fun cancelInternal(owner: Any?, expectedFocus: Any? = null): Unit = withContext(NonCancellable) {
        sessionMutex.withLock {
            if (!matchesAudioFocusRequest(expectedFocus, activeFocusToken)) return@withLock
            val session = activeSession
            if (session != null && !ownsAudioSession(owner, session.owner)) return@withLock
            if (session == null) {
                abandonAudioFocus()
                mutableState.value = AudioCaptureState.STOPPED
                return@withLock
            }
            mutableState.value = AudioCaptureState.STOPPING
            session.active.set(false)
            runCatching { session.recorder.stop() }
            session.completed.await()
            session.captureJob?.join()
            activeSession = null
            runCatching { session.recorder.release() }
            abandonAudioFocus()
            val cleared = withContext(Dispatchers.IO) {
                deletePrivateTemporaryFile(session.temporaryFile)
            }
            mutableState.value = if (cleared) {
                AudioCaptureState.STOPPED
            } else {
                AudioCaptureState.FAILED
            }
            if (!cleared) {
                throw AudioCaptureException(
                    AudioCaptureFailure.STORAGE_UNAVAILABLE,
                    "无法清理录音临时文件",
                )
            }
        }
    }

    private suspend fun capturePcm(session: CaptureSession) {
        val buffer = ByteArray(session.bufferSize)
        var capturedBytes = 0L
        try {
            FileOutputStream(session.temporaryFile).use { output ->
                while (session.active.get() && capturedBytes < session.maximumPcmBytes) {
                    val requestedBytes = minOf(
                        buffer.size.toLong(),
                        session.maximumPcmBytes - capturedBytes,
                    ).toInt()
                    val readBytes = session.recorder.read(
                        buffer,
                        0,
                        requestedBytes,
                        AudioRecord.READ_BLOCKING,
                    )
                    when {
                        readBytes > 0 -> {
                            output.write(buffer, 0, readBytes)
                            capturedBytes += readBytes
                            val boundary = session.endpointDetector?.appendPcm16Le(
                                bytes = buffer,
                                count = readBytes,
                            )
                            if (boundary != null &&
                                boundary != SpeechEndpointBoundary.CONTINUE
                            ) {
                                session.speechEndpoint?.complete(boundary)
                                session.active.set(false)
                            }
                        }
                        readBytes == 0 -> Unit
                        else -> error("AudioRecord 读取失败")
                    }
                }
            }
        } catch (exception: Exception) {
            if (session.active.get()) {
                session.active.set(false)
                session.failure = exception
                session.speechEndpoint?.completeExceptionally(exception)
                runCatching { session.recorder.stop() }
                mutableState.value = AudioCaptureState.FAILED
            }
        } finally {
            // 到达时长上限或端点后也必须停止硬件；不能只退出文件写入循环。
            session.active.set(false)
            runCatching { session.recorder.stop() }
            buffer.fill(0)
            session.speechEndpoint?.complete(
                if (capturedBytes >= session.maximumPcmBytes) {
                    SpeechEndpointBoundary.MAXIMUM_REACHED
                } else {
                    SpeechEndpointBoundary.STOPPED
                },
            )
            session.completed.complete(Unit)
        }
    }

    private fun requestAudioFocus(owner: Any?) {
        val focusToken = Any()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange < 0) {
                    adapterScope.launch { cancelInternal(owner, expectedFocus = focusToken) }
                }
            }
            .build()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AudioCaptureException(
                AudioCaptureFailure.AUDIO_FOCUS_DENIED,
                "麦克风正在被其他应用使用",
            )
        }
        activeFocusRequest = focusRequest
        activeFocusToken = focusToken
    }

    private fun abandonAudioFocus() {
        activeFocusToken = null
        activeFocusRequest?.let { request ->
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        activeFocusRequest = null
    }

    private fun scrubStaleTemporaryFiles(): Boolean {
        val directory = File(context.cacheDir, PRIVATE_AUDIO_DIRECTORY)
        if (!directory.exists()) return true
        val staleFiles = directory.listFiles { file ->
            file.isFile && file.name.startsWith(TEMPORARY_FILE_PREFIX) &&
                file.name.endsWith(TEMPORARY_FILE_SUFFIX)
        } ?: return false
        return staleFiles.all(::deletePrivateTemporaryFile)
    }

    private fun deletePrivateTemporaryFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.delete()) return true
        return runCatching {
            FileOutputStream(file, false).use { output ->
                output.channel.truncate(0)
            }
            file.delete()
        }.getOrDefault(false)
    }

    private class CaptureSession(
        val owner: Any?,
        val recorder: AudioRecord,
        val temporaryFile: File,
        val bufferSize: Int,
        val maximumPcmBytes: Long,
        val endpointDetector: PcmSpeechEndpointDetector?,
        val speechEndpoint: CompletableDeferred<SpeechEndpointBoundary>?,
        val active: AtomicBoolean = AtomicBoolean(true),
        val completed: CompletableDeferred<Unit> = CompletableDeferred(),
        var captureJob: Job? = null,
        var failure: Throwable? = null,
    )

    private companion object {
        const val MIN_DURATION_MS = 200
        const val MAX_DURATION_MS = 60_000
        const val BYTES_PER_SAMPLE = 2
        const val MIN_BUFFER_BYTES = 3_200
        const val PRIVATE_AUDIO_DIRECTORY = "private-audio"
        const val TEMPORARY_FILE_PREFIX = "capture-"
        const val TEMPORARY_FILE_SUFFIX = ".pcm.tmp"
    }
}

/**
 * Android 录音失败。不包含临时文件路径或原始音频。
 */
class AudioCaptureException(
    val failure: AudioCaptureFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Android 录音失败类型。
 */
enum class AudioCaptureFailure {
    ALREADY_RECORDING,
    PERMISSION_DENIED,
    AUDIO_FOCUS_DENIED,
    DEVICE_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    READ_FAILED,
    NO_ACTIVE_RECORDING,
}
