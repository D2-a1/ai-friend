package com.aifriend.feature.knowledge

import com.aifriend.contract.api.AssistantKnowledgeApi
import com.aifriend.contract.api.PrivacyApi
import com.aifriend.core.security.SessionCredentialStore
import com.aifriend.feature.auth.AuthSessionRepository
import java.io.IOException
import javax.inject.Inject
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import retrofit2.Retrofit

/** 只供知识功能使用。每次HTTP尝试冻结当前账号Token；刷新重试重新绑定，不沿用动态账号拦截器结果。 */
class KnowledgeApiFactory @Inject constructor(
    private val retrofit: Retrofit,
    private val client: OkHttpClient,
    private val credentials: SessionCredentialStore,
    private val auth: AuthSessionRepository,
) {
    internal fun assistant(login: KnowledgeLogin): AssistantKnowledgeApi = bound(login).create(AssistantKnowledgeApi::class.java)
    internal fun privacy(login: KnowledgeLogin): PrivacyApi = bound(login).create(PrivacyApi::class.java)

    private fun bound(login: KnowledgeLogin): Retrofit {
        val owner = login.expected.userId
        val stored = credentials.session.value
        fun current() = auth.loginEpoch == login.epoch && auth.session.value?.userId == owner &&
            auth.session.value?.userStatus == "ACTIVE" && credentials.session.value?.userId == owner &&
            credentials.session.value?.userStatus == "ACTIVE"
        if (stored == null || stored.userId != owner || stored.userStatus != "ACTIVE" || !current())
            throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
        val boundClient = client.newBuilder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            // 在原有应用拦截器之后覆盖Authorization，禁止A的请求在切号后使用B的Token。
            .addInterceptor(KnowledgeIdentityInterceptor(retrofit.baseUrl(), stored.accessToken, ::current))
            .build()
        return retrofit.newBuilder().client(boundClient).build()
    }
}

internal class KnowledgeIdentityInterceptor(
    private val origin: HttpUrl,
    private val token: String,
    private val isCurrent: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!isCurrent() || url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            !url.encodedPath.startsWith(origin.encodedPath)) throw IOException("KNOWLEDGE_AUTH_OR_ORIGIN_CHANGED")
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (!isCurrent()) { response.close(); throw IOException("KNOWLEDGE_AUTH_CHANGED") }
        val body = response.body ?: return response
        if (body.contentLength() > MAX_BYTES) { response.close(); throw IOException("KNOWLEDGE_RESPONSE_LIMIT") }
        return response.newBuilder().body(LimitedBody(body)).build()
    }

    private class LimitedBody(private val delegate: ResponseBody) : ResponseBody() {
        private val limited: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                var count = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (count > MAX_BYTES) throw IOException("KNOWLEDGE_RESPONSE_LIMIT")
                    val n = super.read(sink, minOf(byteCount, MAX_BYTES + 1 - count))
                    if (n > 0) count += n
                    if (count > MAX_BYTES) throw IOException("KNOWLEDGE_RESPONSE_LIMIT")
                    return n
                }
            }.buffer()
        }
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = limited
        override fun close() { try { super.close() } finally { delegate.close() } }
    }
    companion object { const val MAX_BYTES = 262144L }
}
