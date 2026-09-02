package com.aifriend.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务时钟配置。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration
public class TimeConfiguration {

    /**
     * 创建业务时钟配置。
     */
    public TimeConfiguration() {
    }

    /**
     * 提供 UTC 系统时钟。
     *
     * @return UTC 时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
