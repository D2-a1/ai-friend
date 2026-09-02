package com.aifriend.dialect.application;

/**
 * 已通过签名、文件哈希、固定方言和服务端兼容性校验的方言包。
 *
 * @param manifest 已验证清单
 * @param acousticCalibration 已验证声学校准参数
 * @author Codex
 * @since 1.0.0
 */
public record VerifiedDialectPackage(
        DialectPackageManifest manifest,
        DialectAcousticCalibration acousticCalibration) {
}
