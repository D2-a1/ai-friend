package com.aifriend.feature.knowledge

import java.io.IOException
import java.lang.reflect.Proxy
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** 直接运行生产拦截器；Chain为纯内存替身，不创建套接字。 */
class KnowledgeIdentityInterceptorTest {
    @Test fun overridesDynamicTokenWithOriginalOwnerToken() {
        val f = Fixture(); f.run().close()
        assertEquals("Bearer original-owner", f.sent!!.header("Authorization"))
    }
    @Test fun changedLoginPreventsAnyRequest() {
        val f = Fixture(); f.current = false
        fails { f.run() }; assertNull(f.sent)
    }
    @Test fun changedLoginAfterDispatchRejectsAndClosesResponse() {
        val f = Fixture(); val body = TrackedBody(10); f.body = body; f.afterDispatch = { f.current = false }
        fails { f.run() }; assertTrue(body.closed)
    }
    @Test fun differentHostSchemePortOrBasePathCannotReceiveCredentials() {
        listOf("https://other.invalid/api/v1/test", "http://example.invalid/api/v1/test", "https://example.invalid:444/api/v1/test", "https://example.invalid/api/v10/test").forEach {
            val f = Fixture(); f.url = it; fails { f.run() }; assertNull(f.sent)
        }
    }
    @Test fun oversizedDeclaredBodyIsRejectedAndClosed() {
        val f = Fixture(); val body = TrackedBody(KnowledgeIdentityInterceptor.MAX_BYTES + 1); f.body = body
        fails { f.run() }; assertTrue(body.closed)
    }
    @Test fun oversizedUnknownLengthStreamIsBounded() {
        val f = Fixture(); val body = TrackedBody(-1, KnowledgeIdentityInterceptor.MAX_BYTES.toInt() + 20); f.body = body
        f.run().use { response -> fails { response.body!!.bytes() } }
        assertTrue(body.closed)
    }
    @Test fun exactLimitBodyIsReadable() {
        val f = Fixture(); f.body = ByteArray(KnowledgeIdentityInterceptor.MAX_BYTES.toInt()).toResponseBody()
        f.run().use { assertEquals(KnowledgeIdentityInterceptor.MAX_BYTES.toInt(), it.body!!.bytes().size) }
    }
    private class TrackedBody(private val length: Long, bytes: Int = 0) : ResponseBody() {
        var closed = false
        private val buffer = Buffer().write(ByteArray(bytes))
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source() = buffer
        override fun close() { closed = true; super.close() }
    }
    private class Fixture {
        var current = true; var url = "https://example.invalid/api/v1/test"
        var body: ResponseBody = "{}".toResponseBody(); var sent: Request? = null
        var afterDispatch: () -> Unit = {}
        fun run(): Response {
            val request = Request.Builder().url(url).header("Authorization", "Bearer dynamic-other-owner").build()
            val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
                when (method.name) {
                    "request" -> request
                    "proceed" -> {
                        sent = args[0] as Request; afterDispatch()
                        Response.Builder().request(sent!!).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
                    }
                    else -> error("Unexpected chain operation")
                }
            } as Interceptor.Chain
            return KnowledgeIdentityInterceptor("https://example.invalid/api/v1/".toHttpUrl(), "original-owner") { current }.intercept(chain)
        }
    }
    private fun fails(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Expected bounded rejection")
    }
}
