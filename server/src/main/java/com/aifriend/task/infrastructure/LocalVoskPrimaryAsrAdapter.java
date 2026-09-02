package com.aifriend.task.infrastructure;

import java.io.IOException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.aifriend.task.application.TaskAsrEngineDescriptor;
import com.aifriend.task.application.TaskAsrEngineResult;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskPrimaryAsrEnginePort;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 已验签主方言 Vosk 模型的本地识别适配器。
 *
 * <p>模型归档只从部署文件系统读取，不自动下载；缺配置、摘要不符或原生库不可用时
 * 统一失败关闭。通用普通话模型不得配置到本端口冒充武冈话主模型。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class LocalVoskPrimaryAsrAdapter implements TaskPrimaryAsrEnginePort {

    private final VoskTaskAsrEngine engine;

    /**
     * 创建主方言本地识别适配器。
     *
     * @param properties 本地任务 ASR 配置
     */
    public LocalVoskPrimaryAsrAdapter(TaskAsrProperties properties) {
        this.engine = new VoskTaskAsrEngine(
                properties.primary(), TaskAsrSource.PRIMARY);
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
