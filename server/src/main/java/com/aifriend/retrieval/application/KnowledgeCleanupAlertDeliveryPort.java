package com.aifriend.retrieval.application;

import java.util.Objects;
import java.util.Optional;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel;

/**
 * 匿名清理告警通道。实现须在事务外有界调用并验证真实送达，不能把HTTP受理当送达。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeCleanupAlertDeliveryPort {
    /**
     * 使用稳定事件身份幂等投递或查询原投递，禁止每次重试产生新事件。
     * 适配器须校验通道回执真实性、目标及事件身份；超时或受理中返回空，取消原样传播。
     * @param event 仅含类别和累计序号，无用户、原文或异常材料
     * @return 已验证送达的同一事件；没有证据时为空
     */
    Optional<Event> deliverAndVerify(Event event);

    /**
     * 稳定匿名身份；部署级命名空间必须由未来通道配置补充，不能跨环境共用。
     * @param level 有限告警类别
     * @param sequence 从1开始的持久累计序号
     */
    record Event(AlertLevel level, long sequence) {
        /** 拒绝无效身份。 */
        public Event {
            Objects.requireNonNull(level, "level");
            if (sequence < 1) { throw new IllegalArgumentException("INVALID_CLEANUP_ALERT_EVENT"); }
        }
    }
}
