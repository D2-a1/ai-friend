package com.aifriend.core.voice

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 本机方言包、内容模板、Keystore 和 Room 依赖。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceTemplateModule {
    @Binds
    abstract fun bindDialectPackageRegistry(
        implementation: AndroidSignedDialectPackageRegistry,
    ): DialectPackageRegistry

    @Binds
    abstract fun bindLocalVoiceTemplateEngine(
        implementation: MfccDtwLocalVoiceTemplateEngine,
    ): LocalVoiceTemplateEngine

    @Binds
    abstract fun bindVoiceTemplateCipher(
        implementation: AndroidKeystoreVoiceTemplateCipher,
    ): VoiceTemplateCipher

    @Binds
    abstract fun bindLocalVoiceTemplateCoordinator(
        implementation: DefaultLocalVoiceTemplateCoordinator,
    ): LocalVoiceTemplateCoordinator

    @Binds
    abstract fun bindLocalRoutineCommandTemplateStore(
        implementation: DefaultLocalVoiceTemplateCoordinator,
    ): LocalRoutineCommandTemplateStore

    companion object {
        @Provides
        @Singleton
        fun provideLocalVoiceTemplateDatabase(
            @ApplicationContext context: Context,
        ): LocalVoiceTemplateDatabase = Room.databaseBuilder(
            context,
            LocalVoiceTemplateDatabase::class.java,
            "ai_friend_voice_templates.db",
        ).build()
    }
}
