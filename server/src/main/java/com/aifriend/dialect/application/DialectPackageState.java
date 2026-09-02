package com.aifriend.dialect.application;

/**
 * 当前方言包加载状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum DialectPackageState {
    /** 部署配置没有启用方言语音能力。 */
    DISABLED,
    /** 方言包已通过全部校验，可供本地声学引擎使用。 */
    ACTIVE,
    /** 仅个人测试使用的固定基础声学参数，不是正式武冈话包。 */
    BASIC_EXPERIENCE,
    /** 配置或方言包无效，方言语音能力失败关闭。 */
    INVALID
}
