package com.aifriend.shared.logging;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 单元测试使用的同步内存日志捕获器。
 *
 * <p>只捕获指定类的已格式化消息，并在关闭时移除 Appender，避免测试之间互相污染。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class TestLogCapture implements AutoCloseable {
    /** 被捕获的 Logback 日志组件。 */
    private final Logger logger;
    /** 当前测试独占的内存 Appender。 */
    private final ListAppender<ILoggingEvent> appender;

    private TestLogCapture(Class<?> loggerType) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerType);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(appender);
    }

    /**
     * 为指定类创建并启动内存日志捕获器。
     *
     * @param loggerType 被捕获日志所属的类
     * @return 已启动的日志捕获器
     */
    public static TestLogCapture forClass(Class<?> loggerType) {
        return new TestLogCapture(loggerType);
    }

    /**
     * 返回当前已捕获的格式化日志消息。
     *
     * @return 不含日志模板参数对象的消息快照
     */
    public List<String> messages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 移除并停止当前测试的内存 Appender。 */
    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
