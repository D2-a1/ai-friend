package com.aifriend.contract

import com.aifriend.contract.api.VoiceCollectionApi
import com.aifriend.contract.model.DeleteVoiceCollectionSampleRequest
import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.HTTP

/** OpenAPI 生成的 Retrofit 请求方法契约测试。 */
class OpenApiRetrofitContractTest {

    @Test
    fun `voice collection deletion uses delete request body`() {
        val method = VoiceCollectionApi::class.java.getDeclaredMethod(
            "deleteVoiceCollectionSample",
            String::class.java,
            String::class.java,
            DeleteVoiceCollectionSampleRequest::class.java,
            Continuation::class.java,
        )

        val http = requireNotNull(method.getAnnotation(HTTP::class.java))
        assertEquals("DELETE", http.method)
        assertEquals("voice-collection-samples/{sampleId}", http.path)
        assertTrue(http.hasBody)
        assertNull(method.getAnnotation(DELETE::class.java))
    }
}
