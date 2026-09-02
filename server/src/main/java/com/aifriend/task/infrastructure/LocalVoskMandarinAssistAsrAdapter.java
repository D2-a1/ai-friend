package com.aifriend.task.infrastructure;

import java.io.IOException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.aifriend.task.application.TaskAsrEngineDescriptor;
import com.aifriend.task.application.TaskAsrEngineResult;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskMandarinAssistAsrEnginePort;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 普通话辅助 Vosk 模型的本地识别适配器。
 *
 * <p>该结果只能作为主方言识别的辅助证据，不能单独决定联系人、确认或微信动作。
 * 模型缺失、摘要不符或原生库不可用时不会回退云端服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class LocalVoskMandarinAssistAsrAdapter
        implements TaskMandarinAssistAsrEnginePort {

    private final VoskTaskAsrEngine engine;

    /**
     * 创建普通话辅助本地识别适配器。
     *
     * @param properties 本地任务 ASR 配置
     */
    public LocalVoskMandarinAssistAsrAdapter(TaskAsrProperties properties) {
        this.engine = new VoskTaskAsrEngine(
                properties.mandarinAssist(), TaskAsrSource.MANDARIN_ASSIST);
    }

    /** {@inheritDoc} */
    @Override
    public TaskAsrEngineDescriptor descriptor() {
        return engine.descriptor();
    }

    /** {@inheritDoc} */
    @Override
    public TaskAsrEngineResult recognize(ValidatedAudioObject audioObject) {
        return engine.recognize(audioObject);
    }

    /**
     * 释放本地模型和本进程创建的临时解压目录。
     *
     * @throws IOException 当临时模型目录无法安全清理时抛出
     */
    @PreDestroy
    public void close() throws IOException {
        engine.close();
    }
}
