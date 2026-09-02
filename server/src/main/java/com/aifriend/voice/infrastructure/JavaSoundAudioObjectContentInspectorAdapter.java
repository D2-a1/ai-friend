package com.aifriend.voice.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.application.AudioObjectContentInspectorPort;
import com.aifriend.voice.application.AudioObjectInspection;

/**
 * 使用 JDK Java Sound 实际解析 WAV 的音频内容检查适配器。
 *
 * <p>当前不引入大型或原生解码依赖。AAC、MP4 和 Ogg 即使通过上传魔数检查，
 * 在未配置可靠解码适配器前仍统一失败关闭，不伪造真实时长。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JavaSoundAudioObjectContentInspectorAdapter
        implements AudioObjectContentInspectorPort {

    /**
     * 创建使用 JDK Java Sound 的 WAV 内容检查适配器。
     */
    public JavaSoundAudioObjectContentInspectorAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public AudioObjectInspection inspect(String mediaType, byte[] audioContent) {
        if (!"audio/wav".equals(mediaType)) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        try (ByteArrayInputStream source = new ByteArrayInputStream(audioContent);
                AudioInputStream audioInput = AudioSystem.getAudioInputStream(source)) {
            AudioFormat format = audioInput.getFormat();
            long frameLength = audioInput.getFrameLength();
            float frameRate = format.getFrameRate();
            if (frameLength <= 0 || !Float.isFinite(frameRate) || frameRate <= 0) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            long durationMs = Math.round(frameLength * 1_000.0D / frameRate);
            if (durationMs > Integer.MAX_VALUE) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            return new AudioObjectInspection((int) durationMs);
        } catch (UnsupportedAudioFileException | IOException exception) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }
}
