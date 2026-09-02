package com.aifriend.task.application;

import java.time.Instant;

/**
 * 不含任意脚本或坐标的有限动作计划。
 *
 * @param planId 当前任务计划编号
 * @param action SEND_AUDIO_AND_TEXT、START_VOICE_CALL 或 START_VIDEO_CALL
 * @param contactId ct_ 前缀联系人编号
 * @param audioObjectId 发送消息时的 TASK 音频编号，可空
 * @param summaryHash 完整复述摘要哈希
 * @param minimumRuleVersion 客户端必须使用的规则版本
 * @param expiresAt 动作计划过期时间
 * @param targetSearchLocator 本次短时计划用于微信精确搜索的稳定定位；不得记录日志
 * @param targetLocatorProof 当前计划一次性稳定定位证明
 * @author Codex
 * @since 1.0.0
 */
public record WechatActionPlanView(
        String planId,
        String action,
        String contactId,
        String audioObjectId,
        String summaryHash,
        String minimumRuleVersion,
        Instant expiresAt,
        String targetSearchLocator,
        WechatTargetLocatorProofView targetLocatorProof) {

    /**
     * 返回不包含联系人稳定定位的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "WechatActionPlanView[planId=" + planId + ", action=" + action
                + ", contactId=" + contactId + ", audioObjectId=" + audioObjectId
                + ", summaryHash=" + summaryHash + ", minimumRuleVersion="
                + minimumRuleVersion + ", expiresAt=" + expiresAt
                + ", targetSearchLocator=<redacted>, targetLocatorProof="
                + targetLocatorProof + "]";
    }
}
