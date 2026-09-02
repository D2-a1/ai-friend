package com.aifriend.retention.infrastructure;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.DeletionTombstoneRestoreCoordinator;
import com.aifriend.retention.application.DisasterRecoveryProperties;

/**
 * 灾备恢复服务上线前的删除墓碑重放闸。
 *
 * <p>普通实例不读取灾备介质。恢复实例只有在当前启动完成可信快照全量重放、摘要验证和
 * 旧账号复活检查后才能继续；缺少真实恢复源时由默认适配器失败关闭。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class DisasterRecoveryStartupGuard implements SmartInitializingSingleton {

    /** 灾备恢复配置。 */
    private final DisasterRecoveryProperties properties;
    /** 删除墓碑全量恢复编排器。 */
    private final DeletionTombstoneRestoreCoordinator restoreCoordinator;

    /**
     * 创建灾备恢复启动闸。
     *
     * @param properties 灾备恢复配置
     * @param restoreCoordinator 删除墓碑全量恢复编排器
     */
    public DisasterRecoveryStartupGuard(
            DisasterRecoveryProperties properties,
            DeletionTombstoneRestoreCoordinator restoreCoordinator) {
        this.properties = properties;
        this.restoreCoordinator = restoreCoordinator;
    }

    /**
     * 在恢复实例对外提供服务前拒绝未完成墓碑重放的启动。
     *
     * @throws RuntimeException 当前启动未能完成可信墓碑恢复与核验时抛出
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (properties.restoreMode()) {
            restoreCoordinator.restoreAndVerify();
        }
    }
}
