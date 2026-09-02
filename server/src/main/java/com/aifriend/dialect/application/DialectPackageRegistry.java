package com.aifriend.dialect.application;

import java.util.Optional;

/**
 * 当前随应用发布且已验证方言包的只读注册表。
 *
 * <p>v1.0 不提供后台下载、热更新或用户切换接口；新包只随新的正式应用版本发布。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DialectPackageRegistry {

    /**
     * 获取当前方言包加载状态。
     *
     * @return 禁用、有效或校验失败状态
     */
    DialectPackageState state();

    /**
     * 获取当前已验证方言包。
     *
     * @return 有效包；关闭或校验失败时为空
     */
    Optional<VerifiedDialectPackage> findActive();
}
