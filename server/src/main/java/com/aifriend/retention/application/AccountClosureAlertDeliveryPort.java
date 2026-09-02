package com.aifriend.retention.application;

/**
 * 账号注销匿名告警的外部通知端口。
 *
 * <p>实现不得补充用户、注销作业、消息正文或联系方式到外发载荷。提交成功只表示
 * 供应商受理，必须使用受理流水号再次复验，只有供应商明确报告 DELIVERED 才能形成
 * 稳定回执。实现不得在结果不明时伪造成功。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureAlertDeliveryPort {

    /**
     * 向固定责任组提交一项匿名告警。
     *
     * @param delivery 尚未获得供应商流水号的匿名告警
     * @return 供应商真实返回的受理流水号
     * @throws RuntimeException 通道不可用、结果不明或响应无效时抛出
     */
    AccountClosureAlertSubmission submit(AccountClosureAlertDelivery delivery);

    /**
     * 查询已经提交的告警最终投递状态。
     *
     * @param delivery 已携带供应商流水号的匿名告警
     * @return 供应商报告的待处理、已送达或明确失败状态
     * @throws RuntimeException 通道不可用、结果不明或响应无效时抛出
     */
    AccountClosureAlertDeliveryVerification verify(AccountClosureAlertDelivery delivery);
}
