package com.aifriend.feature.knowledge

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 仅注册无状态只读仓储；未开启管理权限，不在构造时访问网络。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class KnowledgeModule {
    @Binds abstract fun bindKnowledgeRepository(implementation: DefaultKnowledgeRepository): KnowledgeRepository
    @Binds abstract fun bindQuestionSpeechRecognizer(implementation: DefaultQuestionSpeechRecognizer): QuestionSpeechRecognizer
    @Binds abstract fun bindKnowledgeVoiceFactory(implementation: AndroidKnowledgeVoiceFactory): KnowledgeVoiceFactory
}
