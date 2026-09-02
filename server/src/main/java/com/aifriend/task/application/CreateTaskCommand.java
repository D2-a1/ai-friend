package com.aifriend.task.application;

/**
 * 创建当前方言任务命令。
 *
 * @param clientTaskId 客户端当前任务随机编号
 * @param audioObjectId TASK 用途音频对象公开编号
 * @param previousConfirmedContactId 仅连续会话可带的上次联系人编号，可空
 * @param clientContext 客户端规则和模型版本
 * @author Codex
 * @since 1.0.0
 */
public record CreateTaskCommand(
        String clientTaskId,
        String audioObjectId,
        String previousConfirmedContactId,
        TaskClientContext clientContext) {

    /**
     * 生成不含音频内容的稳定请求指纹输入。
     *
     * @return 请求语义拼接结果
     */
    public String fingerprintInput() {
        return clientTaskId + "|" + audioObjectId + "|"
                + (previousConfirmedContactId == null ? "" : previousConfirmedContactId)
                + "|" + clientContext.fingerprintInput();
    }
}
