package com.aifriend.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aifriend.task.application.TaskSemanticModelProperties.ThinkingMode;
import com.aifriend.task.application.TaskSemanticModelProperties.TokenLimitField;

class TaskSemanticModelPropertiesTest {

    private static final String KEY = "test-semantic-model-key";
    private static final URI ENDPOINT =
            URI.create("https://chat.vendor-one.net/v1/chat/completions");

    @Test
    void disabledConfigurationMayKeepPlaceholderWithoutCredential() {
        TaskSemanticModelProperties properties = new TaskSemanticModelProperties(
                false,
                URI.create("https://model-gateway.example.invalid/v1/chat/completions"),
                Set.of("model-gateway.example.invalid"),
                "",
                "",
                TokenLimitField.MAX_TOKENS,
                180,
                0.0D,
                ThinkingMode.OMIT,
                Duration.ofSeconds(2),
                Duration.ofSeconds(6));

        assertThat(properties.enabled()).isFalse();
    }

    @Test
    void enabledConfigurationRequiresExactExternallyAllowedHostAndCredential() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                URI.create("http://chat.vendor-one.net/v1/chat/completions"),
                Set.of("chat.vendor-one.net"), KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                URI.create("https://127.0.0.1/v1/chat/completions"),
                Set.of("127.0.0.1"), KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                ENDPOINT, Set.of("chat.vendor-two.net"), KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                ENDPOINT, Set.of("*.vendor-one.net"), KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                URI.create("https://chat.vendor-one.net/v1/responses"),
                Set.of("chat.vendor-one.net"), KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                ENDPOINT, Set.of("chat.vendor-one.net"), ""));

        assertThat(properties(ENDPOINT, Set.of("chat.vendor-one.net"), KEY).enabled())
                .isTrue();
        assertThat(properties(
                URI.create("https://gateway.vendor-two.cn/compatible/v1/chat/completions"),
                Set.of("gateway.vendor-two.cn"), KEY).enabled())
                .isTrue();
    }

    @Test
    void allowedHostsAreNormalizedButNeverPatternMatched() {
        TaskSemanticModelProperties properties = properties(
                ENDPOINT, Set.of("  CHAT.VENDOR-ONE.NET  "), KEY);

        assertThat(properties.allowedHosts()).containsExactly("chat.vendor-one.net");
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                URI.create("https://sub.chat.vendor-one.net/v1/chat/completions"),
                Set.of("chat.vendor-one.net"), KEY));
    }

    @Test
    void protocolSpecificRequestSettingsRemainExternalConfiguration() {
        TaskSemanticModelProperties properties = new TaskSemanticModelProperties(
                true, ENDPOINT, Set.of("chat.vendor-one.net"),
                "semantic-model-v1", KEY,
                TokenLimitField.MAX_COMPLETION_TOKENS, 256, 0.25D,
                ThinkingMode.DISABLED,
                Duration.ofSeconds(2), Duration.ofSeconds(6));

        assertThat(properties.tokenLimitField())
                .isEqualTo(TokenLimitField.MAX_COMPLETION_TOKENS);
        assertThat(properties.maxOutputTokens()).isEqualTo(256);
        assertThat(properties.temperature()).isEqualTo(0.25D);
        assertThat(properties.thinkingMode()).isEqualTo(ThinkingMode.DISABLED);
    }

    @Test
    void outputSettingsMustStayWithinBoundedProtocol() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSemanticModelProperties(
                true, ENDPOINT, Set.of("chat.vendor-one.net"),
                "semantic-model-v1", KEY, TokenLimitField.MAX_TOKENS,
                31, 0.0D, ThinkingMode.OMIT,
                Duration.ofSeconds(2), Duration.ofSeconds(6)));
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSemanticModelProperties(
                true, ENDPOINT, Set.of("chat.vendor-one.net"),
                "semantic-model-v1", KEY, TokenLimitField.MAX_TOKENS,
                180, Double.NaN, ThinkingMode.OMIT,
                Duration.ofSeconds(2), Duration.ofSeconds(6)));
    }

    @Test
    void configurationTextMustRedactCredential() {
        String text = properties(ENDPOINT, Set.of("chat.vendor-one.net"), KEY).toString();

        assertThat(text).doesNotContain(KEY).contains("apiKey=***");
    }

    @Test
    void timeoutMustStayWithinInteractiveBoundary() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TaskSemanticModelProperties(
                        true, ENDPOINT, Set.of("chat.vendor-one.net"),
                        "semantic-model-v1", KEY, TokenLimitField.MAX_TOKENS,
                        180, 0.0D, ThinkingMode.OMIT,
                        Duration.ofSeconds(2), Duration.ofSeconds(11)));
    }

    private TaskSemanticModelProperties properties(
            URI endpoint,
            Set<String> allowedHosts,
            String key) {
        return new TaskSemanticModelProperties(
                true, endpoint, allowedHosts, "semantic-model-v1", key,
                TokenLimitField.MAX_TOKENS, 180, 0.0D, ThinkingMode.OMIT,
                Duration.ofSeconds(2), Duration.ofSeconds(6));
    }
}