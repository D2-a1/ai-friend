package com.aifriend.core.network

import com.aifriend.BuildConfig
import com.aifriend.contract.api.AudioApi
import com.aifriend.contract.api.AssistantKnowledgeApi
import com.aifriend.contract.model.AssistantPurpose
import com.aifriend.contract.model.AssistantSessionState
import com.aifriend.contract.model.AssistantResultStatus
import com.aifriend.contract.model.AssistantAnswerMode
import com.aifriend.contract.model.AssistantReasonCode
import com.aifriend.contract.model.KnowledgeRetrievalMode
import com.aifriend.contract.model.KnowledgeGraphQueryType
import com.aifriend.contract.model.KnowledgeImportState
import com.aifriend.contract.api.AuthApi
import com.aifriend.contract.api.ContactsApi
import com.aifriend.contract.api.InvitationsApi
import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.api.TasksApi
import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.api.VoiceCollectionApi
import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.AmbiguousCallPreference
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.DialogueStylePreference
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.RecentTaskResultIntent
import com.aifriend.contract.model.RoutineCommandIntent
import com.aifriend.contract.model.WechatPageType
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.SpeechRatePreference
import com.aifriend.contract.model.TaskHistoryDeletionStatus
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.VoiceCollectionCategory
import com.aifriend.contract.model.VoiceCollectionEnvironment
import com.aifriend.contract.model.VoiceCollectionReviewStatus
import com.aifriend.contract.model.VoiceCollectionStatus
import com.aifriend.contract.model.WechatActionType
import com.aifriend.core.security.AccessTokenProvider
import com.aifriend.core.security.SessionCredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.net.URI
import java.time.OffsetDateTime
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 网络基础设施依赖。
 *
 * @author codex
 * @since 2026-07-25
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
            contextual(java.util.UUID::class, UuidSerializer)
            contextual(AssistantPurpose::class, AssistantPurpose.serializer())
            contextual(AssistantSessionState::class, AssistantSessionState.serializer())
            contextual(AssistantResultStatus::class, AssistantResultStatus.serializer())
            contextual(AssistantAnswerMode::class, AssistantAnswerMode.serializer())
            contextual(AssistantReasonCode::class, AssistantReasonCode.serializer())
            contextual(KnowledgeRetrievalMode::class, KnowledgeRetrievalMode.serializer())
            contextual(KnowledgeGraphQueryType::class, KnowledgeGraphQueryType.serializer())
            contextual(KnowledgeImportState::class, KnowledgeImportState.serializer())
            contextual(URI::class, UriSerializer)
            contextual(ConsentType::class, ConsentType.serializer())
            contextual(ConsentDecision::class, ConsentDecision.serializer())
            contextual(SpeechRatePreference::class, SpeechRatePreference.serializer())
            contextual(DialogueStylePreference::class, DialogueStylePreference.serializer())
            contextual(AmbiguousCallPreference::class, AmbiguousCallPreference.serializer())
            contextual(ContactStatus::class, ContactStatus.serializer())
            contextual(WechatPageType::class, WechatPageType.serializer())
            contextual(AudioPurpose::class, AudioPurpose.serializer())
            contextual(AliasCompatibility::class, AliasCompatibility.serializer())
            contextual(SafetyCommandType::class, SafetyCommandType.serializer())
            contextual(VoiceCollectionCategory::class, VoiceCollectionCategory.serializer())
            contextual(VoiceCollectionEnvironment::class, VoiceCollectionEnvironment.serializer())
            contextual(VoiceCollectionStatus::class, VoiceCollectionStatus.serializer())
            contextual(AllowedAction::class, AllowedAction.serializer())
            contextual(ChannelResult::class, ChannelResult.serializer())
            contextual(ConfirmationAction::class, ConfirmationAction.serializer())
            contextual(Intent::class, Intent.serializer())
            contextual(RecentTaskResultIntent::class, RecentTaskResultIntent.serializer())
            contextual(RoutineCommandIntent::class, RoutineCommandIntent.serializer())
            contextual(TaskHistoryDeletionStatus::class, TaskHistoryDeletionStatus.serializer())
            contextual(TaskState::class, TaskState.serializer())
            contextual(VoiceCollectionReviewStatus::class, VoiceCollectionReviewStatus.serializer())
            contextual(WechatActionType::class, WechatActionType.serializer())
            contextual(java.math.BigDecimal::class, BigDecimalNumberSerializer)
        }
    }

    @Provides
    @Singleton
    fun provideAccessTokenProvider(store: SessionCredentialStore): AccessTokenProvider = store

    @Provides
    @Singleton
    fun provideOkHttpClient(accessTokenProvider: AccessTokenProvider): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val token = accessTokenProvider.currentAccessToken()
                val isPublicAuthPath = request.url.encodedPath.contains("/auth/")
                val authenticatedRequest = if (token != null && !isPublicAuthPath) {
                    request.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    request
                }
                chain.proceed(authenticatedRequest)
            }
            .build()

    @Provides
    @Singleton
    @UnauthenticatedUploadClient
    fun provideUnauthenticatedUploadClient(): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideAssistantKnowledgeApi(retrofit: Retrofit): AssistantKnowledgeApi =
        retrofit.create(AssistantKnowledgeApi::class.java)

    @Provides
    @Singleton
    fun providePrivacyApi(retrofit: Retrofit): PrivacyApi = retrofit.create(PrivacyApi::class.java)

    @Provides
    @Singleton
    fun provideInvitationsApi(retrofit: Retrofit): InvitationsApi =
        retrofit.create(InvitationsApi::class.java)

    @Provides
    @Singleton
    fun provideContactsApi(retrofit: Retrofit): ContactsApi =
        retrofit.create(ContactsApi::class.java)

    @Provides
    @Singleton
    fun provideAudioApi(retrofit: Retrofit): AudioApi = retrofit.create(AudioApi::class.java)

    @Provides
    @Singleton
    fun provideVoiceTemplatesApi(retrofit: Retrofit): VoiceTemplatesApi =
        retrofit.create(VoiceTemplatesApi::class.java)

    @Provides
    @Singleton
    fun provideVoiceCollectionApi(retrofit: Retrofit): VoiceCollectionApi =
        retrofit.create(VoiceCollectionApi::class.java)

    @Provides
    @Singleton
    fun provideTasksApi(retrofit: Retrofit): TasksApi = retrofit.create(TasksApi::class.java)
}
