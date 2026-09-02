package com.aifriend.voice.application;

/**
 * 音频内容解码与真实时长检查端口。
 *
 * <p>适配器必须实际解析媒体内容；未支持的容器或编码必须失败关闭，
 * 不得信任客户端声明的 durationMs 伪造检查成功。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioObjectContentInspectorPort {

    /**
     * 解码音频并返回实际内容指标。
     *
     * @param mediaType 经上传凭证固化的媒体类型
     * @param audioContent 原始音频字节，不得记录或持久化
     * @return 实际解码检查结果
     */
    AudioObjectInspection inspect(String mediaType, byte[] audioContent);
}
