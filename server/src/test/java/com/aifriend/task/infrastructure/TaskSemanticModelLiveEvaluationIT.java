package com.aifriend.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.task.application.TaskSemanticModelProperties;
import com.aifriend.task.application.TaskSemanticModelProperties.ThinkingMode;
import com.aifriend.task.application.TaskSemanticModelProperties.TokenLimitField;
import com.aifriend.task.infrastructure.TaskSemanticModelEvaluationHarness.EvaluationMode;

/**
 * 仅由本机评测脚本显式选择的真实模型回放入口。
 *
 * <p>类名使用 {@code IT} 后缀，常规 Surefire 全量测试不会自动执行，也不会意外访问
 * 外部模型。评测端点、白名单、模型、请求方言和密钥全部从独立环境变量读取，既不
 * 进入 Maven 参数也不写入报告。
 */
class TaskSemanticModelLiveEvaluationIT {

    @Test
    void evaluateApprovedGatewayWithoutExecutingAnyTask() throws Exception {
        String endpoint = requiredEnvironment("AI_FRIEND_SEMANTIC_EVAL_ENDPOINT");
        Set<String> allowedHosts = csvEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_ALLOWED_HOSTS");
        String model = requiredEnvironment("AI_FRIEND_SEMANTIC_EVAL_MODEL");
        String apiKey = requiredEnvironment("AI_FRIEND_SEMANTIC_EVAL_API_KEY");
        String provider = requiredEnvironment("AI_FRIEND_SEMANTIC_EVAL_PROVIDER");
        TokenLimitField tokenLimitField = enumEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_TOKEN_LIMIT_FIELD", TokenLimitField.class);
        int maxOutputTokens = integerEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_MAX_OUTPUT_TOKENS");
        double temperature = doubleEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_TEMPERATURE");
        ThinkingMode thinkingMode = enumEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_THINKING_MODE", ThinkingMode.class);
        Duration connectTimeout = Duration.ofSeconds(integerEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_CONNECT_TIMEOUT_SECONDS"));
        Duration readTimeout = Duration.ofSeconds(integerEnvironment(
                "AI_FRIEND_SEMANTIC_EVAL_READ_TIMEOUT_SECONDS"));
        Path dataset = requiredPathProperty(
                "aiFriend.semanticEvaluation.dataset");
        Path reportPath = requiredPathProperty(
                "aiFriend.semanticEvaluation.report");
        EvaluationMode mode = EvaluationMode.valueOf(
                System.getProperty("aiFriend.semanticEvaluation.mode", "SMOKE")
                        .strip().toUpperCase(Locale.ROOT));

        TaskSemanticModelProperties properties = new TaskSemanticModelProperties(
                true, URI.create(endpoint), allowedHosts, model, apiKey,
                tokenLimitField, maxOutputTokens, temperature, thinkingMode,
                connectTimeout, readTimeout);
        ChatCompletionsTaskUnderstandingAdapter adapter =
                new ChatCompletionsTaskUnderstandingAdapter(
                        properties, RestClient.builder(), new ObjectMapper(),
                        CircuitBreakerRegistry.ofDefaults(),
                        BulkheadRegistry.ofDefaults());
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness(adapter::revise);

        var report = harness.evaluate(dataset, provider, model, mode);
        harness.publish(reportPath, report);
        System.out.printf(
                Locale.ROOT,
                "SEMANTIC_EVALUATION mode=%s samples=%d exact=%.4f "
                        + "p95Ms=%d p99Ms=%d releaseGatePassed=%s report=%s%n",
                report.mode(), report.sampleCount(),
                report.exactAccuracy().rate(), report.latency().p95Ms(),
                report.latency().p99Ms(), report.releaseGatePassed(),
                reportPath.toAbsolutePath().normalize());

        if (mode == EvaluationMode.RELEASE_GATE) {
            assertThat(report.releaseGatePassed())
                    .as("真实模型上线门禁失败：%s", report.releaseGateFailures())
                    .isTrue();
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少评测环境变量：" + name);
        }
        return value.strip();
    }

    private Set<String> csvEnvironment(String name) {
        Set<String> values = Arrays.stream(requiredEnvironment(name).split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty()) {
            throw new IllegalStateException("评测主机白名单不能为空：" + name);
        }
        return values;
    }

    private int integerEnvironment(String name) {
        try {
            return Integer.parseInt(requiredEnvironment(name));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("评测整数环境变量格式无效：" + name, exception);
        }
    }

    private double doubleEnvironment(String name) {
        try {
            return Double.parseDouble(requiredEnvironment(name));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("评测小数环境变量格式无效：" + name, exception);
        }
    }

    private <T extends Enum<T>> T enumEnvironment(String name, Class<T> type) {
        try {
            return Enum.valueOf(type, requiredEnvironment(name)
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("评测枚举环境变量格式无效：" + name, exception);
        }
    }

    private Path requiredPathProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少评测路径参数：" + name);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}