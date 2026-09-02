package com.aifriend.voice.application;

import com.aifriend.voice.domain.AudioObject;

/**
 * 对象存储上传目标生成端口。
 *
 * <p>正式环境必须由真实私有对象存储适配器实现；未配置时失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioUploadTargetPort {

    /**
     * 为指定音频对象创建受限上传目标。
     *
     * @param audioObject 音频对象快照
     * @param uploadToken 本次上传秘密，不得记录或持久化明文
     * @return 动态上传地址、方法和必须请求头
     */
    AudioUploadTarget createTarget(AudioObject audioObject, String uploadToken);
}
