package com.aifriend.retention.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifriend.retention.application.AccountClosureAlertDeliveryPort;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentityPort;
import com.aifriend.retention.application.DeletionTombstoneExportPort;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;

/**
 * 数据保留外部端口的失败关闭默认配置。
 *
 * <p>真实通知、身份或灾备适配器缺失时，应用仍可启动，但相关外部操作必须明确失败，
 * 不得回落到日志、本地文件或普通用户身份。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class RetentionUnavailableAdaptersConfiguration {

    /**
     * 创建数据保留失败关闭适配器配置。
     */
    public RetentionUnavailableAdaptersConfiguration() {
    }

    /**
     * 提供失败关闭的告警通知适配器。
     *
     * @return 默认告警通知适配器
     */
    @Bean
    @ConditionalOnMissingBean(AccountClosureAlertDeliveryPort.class)
    public AccountClosureAlertDeliveryPort accountClosureAlertDeliveryPort() {
        return new UnavailableAccountClosureAlertDeliveryAdapter();
    }

    /**
     * 提供失败关闭的告警接手身份适配器。
     *
     * @return 默认告警接手身份适配器
     */
    @Bean
    @ConditionalOnMissingBean(AccountClosureAlertResponderIdentityPort.class)
    public AccountClosureAlertResponderIdentityPort accountClosureAlertResponderIdentityPort() {
        return new UnavailableAccountClosureAlertResponderIdentityAdapter();
    }

    /**
     * 提供失败关闭的墓碑导出适配器。
     *
     * @return 默认墓碑导出适配器
     */
    @Bean
    @ConditionalOnMissingBean(DeletionTombstoneExportPort.class)
    public DeletionTombstoneExportPort deletionTombstoneExportPort() {
        return new UnavailableDeletionTombstoneExportAdapter();
    }

    /**
     * 提供失败关闭的墓碑恢复源适配器。
     *
     * @return 默认墓碑恢复源适配器
     */
    @Bean
    @ConditionalOnMissingBean(DeletionTombstoneRestoreSourcePort.class)
    public DeletionTombstoneRestoreSourcePort deletionTombstoneRestoreSourcePort() {
        return new UnavailableDeletionTombstoneRestoreSourceAdapter();
    }
}
