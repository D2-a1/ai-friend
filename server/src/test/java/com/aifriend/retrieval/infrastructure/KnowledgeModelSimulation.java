package com.aifriend.retrieval.infrastructure;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;

/** 实际状态码处理/超时传输/Chat协议，唯一替身是HTTP客户端，不建立任何网络连接。 */
public final class KnowledgeModelSimulation implements AutoCloseable {
    private final AtomicInteger calls=new AtomicInteger();
    private final ChatCompletionsKnowledgeAnswerAdapter model;
    public KnowledgeModelSimulation(int status,String responseBody) throws Exception {
        var client=mock(CloseableHttpClient.class);
        when(client.execute(any(HttpUriRequest.class))).thenAnswer(c->{
            calls.incrementAndGet();
            var response=mock(CloseableHttpResponse.class);
            when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1,status,"synthetic"));
            when(response.getEntity()).thenReturn(new StringEntity(responseBody,ContentType.APPLICATION_JSON));
            return response;
        });
        var endpoint=URI.create("https://chat.vendor.net/v1/chat/completions");
        var properties=new KnowledgeChatProperties(true,endpoint,Set.of("chat.vendor.net"),"fixture-model","fake-api-key",
                "chat-v1","fixture-report",KnowledgeChatProperties.TokenLimitField.MAX_TOKENS,512,
                KnowledgeChatProperties.ThinkingMode.OMIT,null,Duration.ofSeconds(1),Duration.ofSeconds(4));
        var transport=new PinnedModelHttpTransport(endpoint,"fake-api-key",client,Duration.ofSeconds(1),Duration.ofSeconds(4));
        model=new ChatCompletionsKnowledgeAnswerAdapter(properties,transport,new com.fasterxml.jackson.databind.ObjectMapper(),
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("http-smoke"),
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("http-smoke"));
    }
    public ChatCompletionsKnowledgeAnswerAdapter model() { return model; }
    public int calls() { return calls.get(); }
    @Override public void close() { model.close(); }
}
