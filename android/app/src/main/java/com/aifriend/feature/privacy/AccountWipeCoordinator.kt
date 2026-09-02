package com.aifriend.feature.privacy

import android.content.Context
import com.aifriend.core.security.SessionCredentialStore
import com.aifriend.core.settings.UserSettingsRepository
import com.aifriend.core.voice.LocalVoiceTemplateDatabase
import com.aifriend.core.voice.VoiceTemplateCipher
import com.aifriend.feature.auth.AuthSessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 注销后的最小持久闩；只保存是否必须继续清除，不保存账号或请求信息。 */
interface AccountWipeMarkerStore {
    fun isRequired(): Boolean
    fun markRequired()
    fun clear()
}

/** 注销后需要完成的本机持久数据清除端口。 */
fun interface AccountLocalDataWiper {
    suspend fun wipe()
}

/**
 * 先持久化 WIPE_REQUIRED，再执行本机清除；全部成功后才移除标记。
 *
 * 进程在任一步骤退出时，下次启动仍会先继续清除，不能进入登录页。
 */
@Singleton
class AccountWipeCoordinator @Inject constructor(
    private val markerStore: AccountWipeMarkerStore,
    private val localDataWiper: AccountLocalDataWiper,
) {
    fun isRequired(): Boolean = markerStore.isRequired()

    /** 在返回注销受理回调前同步提交最小持久标记。 */
    fun markRequired() {
        markerStore.markRequired()
    }

    suspend fun beginAcceptedWipe() {
        markRequired()
        resumeRequiredWipe()
    }

    suspend fun resumeRequiredWipe() {
        check(markerStore.isRequired()) { "本机注销清除标记不存在" }
        localDataWiper.wipe()
        markerStore.clear()
    }
}

/** 使用独立 SharedPreferences 同步提交注销清除闩。 */
@Singleton
class SharedPreferencesAccountWipeMarkerStore @Inject constructor(
    @ApplicationContext context: Context,
) : AccountWipeMarkerStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isRequired(): Boolean = preferences.getBoolean(WIPE_REQUIRED, false)

    override fun markRequired() {
        check(preferences.edit().putBoolean(WIPE_REQUIRED, true).commit()) { "注销清除标记写入失败" }
    }

    override fun clear() {
        check(preferences.edit().remove(WIPE_REQUIRED).commit()) { "注销清除标记移除失败" }
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_friend_account_wipe_state"
        const val WIPE_REQUIRED = "wipe_required"
    }
}

/** 清除会话、语音模板 Room/Keystore 和应用私有临时音频。 */
@Singleton
class AndroidAccountLocalDataWiper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialStore: SessionCredentialStore,
    private val authSessionRepository: AuthSessionRepository,
    private val database: LocalVoiceTemplateDatabase,
    private val voiceTemplateCipher: VoiceTemplateCipher,
    private val userSettingsRepository: UserSettingsRepository,
) : AccountLocalDataWiper {
    override suspend fun wipe() = withContext(Dispatchers.IO) {
        // WIPE_REQUIRED 会在会话恢复前续清，因此先幂等清掉全部 owner 设置命名空间。
        userSettingsRepository.clearAll()
        credentialStore.wipe()
        authSessionRepository.clearLocalSession()
        database.clearAllTables()
        voiceTemplateCipher.destroyKey()
        scrubPrivateAudioDirectory()
    }

    private fun scrubPrivateAudioDirectory() {
        val directory = File(context.cacheDir, PRIVATE_AUDIO_DIRECTORY).canonicalFile
        check(directory.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) {
            "临时音频目录越界"
        }
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { target ->
            if (target.isFile) {
                FileOutputStream(target, false).use { output -> output.channel.truncate(0) }
            }
            check(target.delete() || !target.exists()) { "本机临时音频清除失败" }
        }
    }

    private companion object {
        const val PRIVATE_AUDIO_DIRECTORY = "private-audio"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountWipeModule {
    @Binds
    abstract fun bindAccountWipeMarkerStore(
        implementation: SharedPreferencesAccountWipeMarkerStore,
    ): AccountWipeMarkerStore

    @Binds
    abstract fun bindAccountLocalDataWiper(
        implementation: AndroidAccountLocalDataWiper,
    ): AccountLocalDataWiper
}
