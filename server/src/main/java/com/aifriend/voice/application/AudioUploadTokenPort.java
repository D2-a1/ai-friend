package com.aifriend.voice.application;

/**
 * 音频上传秘密生成端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioUploadTokenPort {

    /**
     * 生成至少 256 位、无填充的 URL 安全随机秘密。
     *
     * @return 随机秘密，只能在当前调用内存中短暂存在
     */
    String issue();
}
