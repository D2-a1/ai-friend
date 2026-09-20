package com.aifriend.feature.knowledge

import android.content.Context
import android.content.ContextWrapper
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.feature.guardian.VoskModelAssetInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.Recognizer

/** 复用受检APK资产和安装器，但问答目录/Model/Recognizer均与守护、联系任务隔离。 */
@Singleton
class VoskQuestionDecoderFactory @Inject constructor(@ApplicationContext context: Context) : QuestionDecoderFactory {
    private val installer = VoskModelAssetInstaller(object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = File(context.noBackupFilesDir, "knowledge-question-asr-v1")
    })
    private val installMutex = Mutex()
    override suspend fun open(): QuestionDecoder {
        val directory = installMutex.withLock { installer.install() }
        currentCoroutineContext().ensureActive()
        val model = Model(directory.absolutePath)
        try {
            // 两参数构造器即自由语法，不注入联系人、任务动作或确认词词表。
            val recognizer = Recognizer(model, WavPcmCodec.SAMPLE_RATE.toFloat())
            return object : QuestionDecoder {
                override fun accept(samples: ShortArray): String? =
                    if (recognizer.acceptWaveForm(samples, samples.size)) recognizer.result else null
                override fun finish(): String = recognizer.finalResult
                override fun close() { try { recognizer.close() } finally { model.close() } }
            }
        } catch (failure: Throwable) {
            try { model.close() } catch (_: Throwable) { /* 保留原始失败，绝不打印输入 */ }
            throw failure
        }
    }
}
