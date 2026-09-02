package com.aifriend.voicecollection.application;

/**
 * 单次本机训练输入暂存会话。
 *
 * <p>在 {@link #commit(int)} 成功前关闭会话必须清理全部暂存文件；
 * 实现不得覆盖既有最终目录。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public interface VoiceTrainingInputSink extends AutoCloseable {

    /**
     * 顺序写入一条已完整复验的 WAV 与人工复核文字。
     *
     * @param item 非敏感成员元数据
     * @param audioContent 已验证 WAV 字节，调用结束后由上层清零
     * @param transcriptUtf8 人工复核文字 UTF-8 字节，调用结束后由上层清零
     */
    void write(
            VoiceTrainingInputItem item,
            byte[] audioContent,
            byte[] transcriptUtf8);

    /**
     * 完成标签与清单写入并原子发布最终目录。
     *
     * @param expectedSampleCount 预期成员数
     * @return 最终导出回执
     */
    VoiceTrainingInputExportReceipt commit(int expectedSampleCount);

    /**
     * 关闭会话；未提交时清理全部暂存文件。
     */
    @Override
    void close();
}
