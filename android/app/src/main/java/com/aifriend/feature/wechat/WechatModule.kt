package com.aifriend.feature.wechat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 微信执行边界依赖；正式上下文接入前只绑定失败关闭实现。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WechatModule {
    @Binds
    abstract fun bindExecutionContextProvider(
        implementation: SignedWechatExecutionContextProvider,
    ): WechatExecutionContextProvider

    @Binds
    abstract fun bindRulePackageRegistry(
        implementation: AndroidSignedWechatRulePackageRegistry,
    ): WechatRulePackageRegistry

    @Binds
    abstract fun bindSampleCaptureCoordinator(
        implementation: AndroidWechatSampleCaptureCoordinator,
    ): WechatSampleCaptureCoordinator

    @Binds
    abstract fun bindLocalContactVerificationCoordinator(
        implementation: AndroidWechatLocalContactVerificationCoordinator,
    ): WechatLocalContactVerificationCoordinator

    @Binds
    abstract fun bindTargetLocatorProofVerifier(
        implementation: ConfiguredWechatTargetLocatorProofVerifier,
    ): WechatTargetLocatorProofVerifier

    @Binds
    abstract fun bindSensitivePageEvidencePort(
        implementation: VerifiedContactProfileWechatSensitivePageEvidencePort,
    ): WechatSensitivePageEvidencePort

    @Binds
    abstract fun bindRuntimeVersionProvider(
        implementation: AndroidWechatRuntimeVersionProvider,
    ): WechatRuntimeVersionProvider

    @Binds
    abstract fun bindCalibrationFingerprintProvider(
        implementation: AndroidWechatCalibrationFingerprintProvider,
    ): WechatCalibrationFingerprintProvider

    @Binds
    abstract fun bindCalibrationProfileRegistry(
        implementation: AndroidWechatCalibrationProfileRegistry,
    ): WechatCalibrationProfileRegistry

    @Binds
    abstract fun bindCalibrationCaptureCoordinator(
        implementation: AndroidWechatCalibrationCaptureCoordinator,
    ): WechatCalibrationCaptureCoordinator

    @Binds
    abstract fun bindMessageHandoffPortFactory(
        implementation: AndroidWechatMessageHandoffPortFactory,
    ): WechatMessageHandoffPortFactory

    @Binds
    abstract fun bindMessageCalibrationShareLauncher(
        implementation: AndroidWechatMessageCalibrationShareLauncher,
    ): WechatMessageCalibrationShareLauncher
}
