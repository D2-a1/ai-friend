package com.aifriend.retention.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OperationsTotpPropertiesTest {

    private static final String SECRET =
            "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @Test
    void shouldAcceptProductionConfigurationAndBuildMinimalLink() {
        OperationsTotpProperties properties = new OperationsTotpProperties(
                true,
                "on-call-operator-01",
                SECRET.toLowerCase(java.util.Locale.ROOT),
                URI.create(
                        "https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/"));

        assertThat(properties.secretBase32()).isEqualTo(SECRET);
        assertThat(properties.acknowledgementUri(
                UUID.fromString("12345678-1234-1234-1234-123456789abc")))
                .hasToString(
                        "https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/"
                                + "12345678-1234-1234-1234-123456789abc/acknowledgement");
        assertThat(properties.toString())
                .doesNotContain("on-call-operator-01")
                .doesNotContain(SECRET);
    }

    @Test
    void shouldAllowSafePlaceholderOnlyWhileDisabled() {
        OperationsTotpProperties properties = new OperationsTotpProperties(
                false,
                "",
                "",
                URI.create(
                        "https://api.example.com/api/v1/operations/"
                                + "account-closure-alert-deliveries/"));

        assertThat(properties.enabled()).isFalse();
    }

    @Test
    void shouldRejectPlaceholderHostOrWeakSecretWhenEnabled() {
        assertThatThrownBy(() -> new OperationsTotpProperties(
                true,
                "on-call-operator-01",
                "ABCDEF",
                URI.create(
                        "https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OperationsTotpProperties(
                true,
                "on-call-operator-01",
                SECRET,
                URI.create(
                        "https://api.example.com/api/v1/operations/"
                                + "account-closure-alert-deliveries/")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
