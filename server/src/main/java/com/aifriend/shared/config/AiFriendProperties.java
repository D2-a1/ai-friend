package com.aifriend.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 应用自定义配置。
 *
 * @param api API 配置
 * @param retention 留存配置
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend")
public record AiFriendProperties(
        @NotNull @Valid Api api,
        @NotNull @Valid Retention retention) {

    /**
     * API 配置。
     *
     * @param version 契约版本
     */
    public record Api(@NotBlank String version) {
    }

    /**
     * 数据留存配置。
     *
     * @param temporaryAudioMaxAge 临时音频最长留存时间
     * @param messageContentMaxAge 消息正文最长留存时间
     */
    public record Retention(
            @NotNull Duration temporaryAudioMaxAge,
            @NotNull Duration messageContentMaxAge) {

        /** 最长允许留存时间。 */
        private static final Duration MAXIMUM_AGE = Duration.ofHours(24);

        /**
         * 复验敏感数据留存配置不超过 24 小时硬上限。
         */
        public Retention {
            Objects.requireNonNull(
                    temporaryAudioMaxAge, "temporaryAudioMaxAge must not be null");
            Objects.requireNonNull(
                    messageContentMaxAge, "messageContentMaxAge must not be null");
            requireAllowedAge(temporaryAudioMaxAge, "临时音频");
            requireAllowedAge(messageContentMaxAge, "消息正文");
        }

        private static void requireAllowedAge(Duration value, String label) {
            if (value.isZero() || value.isNegative() || value.compareTo(MAXIMUM_AGE) > 0) {
                throw new IllegalArgumentException(label + "留存时间必须大于 0 且不超过 24 小时");
            }
        }
    }
}
