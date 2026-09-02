package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifriend.retention.application.AccountClosureAlertDeliveryPort;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentityPort;
import com.aifriend.retention.application.DeletionTombstoneExportPort;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;

/**
 * 数据保留失败关闭默认适配器配置测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class RetentionUnavailableAdaptersConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionUnavailableAdaptersConfiguration.class);

    /**
     * 验证没有真实外部适配器时四个端口均存在失败关闭实现。
     */
    @Test
    void shouldProvideAllFailClosedAdaptersWhenExternalProvidersAreMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AccountClosureAlertDeliveryPort.class);
            assertThat(context).hasSingleBean(AccountClosureAlertResponderIdentityPort.class);
            assertThat(context).hasSingleBean(DeletionTombstoneExportPort.class);
            assertThat(context).hasSingleBean(DeletionTombstoneRestoreSourcePort.class);
            assertThat(context.getBean(AccountClosureAlertDeliveryPort.class))
                    .isInstanceOf(UnavailableAccountClosureAlertDeliveryAdapter.class);
            assertThat(context.getBean(AccountClosureAlertResponderIdentityPort.class))
                    .isInstanceOf(UnavailableAccountClosureAlertResponderIdentityAdapter.class);
            assertThat(context.getBean(DeletionTombstoneExportPort.class))
                    .isInstanceOf(UnavailableDeletionTombstoneExportAdapter.class);
            assertThat(context.getBean(DeletionTombstoneRestoreSourcePort.class))
                    .isInstanceOf(UnavailableDeletionTombstoneRestoreSourceAdapter.class);
        });
    }
}
