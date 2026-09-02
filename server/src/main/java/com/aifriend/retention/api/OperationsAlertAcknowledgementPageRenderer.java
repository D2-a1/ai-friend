package com.aifriend.retention.api;

import org.springframework.stereotype.Component;

import com.aifriend.retention.application.AccountClosureAlertAcknowledgement;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementTiming;

/**
 * 注销告警接手确认中文页面渲染器。
 *
 * <p>页面内容完全固定，不插入投递标识、用户数据、异常消息或验证码。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class OperationsAlertAcknowledgementPageRenderer {

    /**
     * 创建固定中文页面渲染器。
     */
    public OperationsAlertAcknowledgementPageRenderer() {
    }

    /**
     * 渲染动态验证码输入页。
     *
     * @return 不包含业务标识的固定中文 HTML
     */
    public String formPage() {
        return page(
                "确认接手告警",
                """
                <p>请输入认证器中当前显示的六位动态验证码。</p>
                <form method="post">
                  <label for="code">动态验证码</label>
                  <input id="code" name="code" type="text" inputmode="numeric"
                         autocomplete="one-time-code" pattern="[0-9]{6}"
                         maxlength="6" required autofocus>
                  <button type="submit">确认接手</button>
                </form>
                <p class="hint">验证码只用于本次接手，不会保存。</p>
                """);
    }

    /**
     * 渲染接手成功页。
     *
     * @param acknowledgement 已持久化的唯一接手结果
     * @return 不包含业务标识的固定中文 HTML
     */
    public String successPage(AccountClosureAlertAcknowledgement acknowledgement) {
        String message = acknowledgement.timing()
                == AccountClosureAlertAcknowledgementTiming.TIMELY
                ? "已在截止时间前完成接手。"
                : "已完成接手，但原告警已进入迟到升级流程。";
        return page(
                "接手成功",
                "<p>" + message + "</p><p>请返回服务器继续核查匿名注销作业状态。</p>");
    }

    /**
     * 渲染不泄露投递存在性和验证细节的失败页。
     *
     * @param retryLater 是否提示稍后重试
     * @return 固定中文 HTML
     */
    public String failurePage(boolean retryLater) {
        String message = retryLater
                ? "运维身份验证暂不可用，请稍后再试。"
                : "无法确认接手。请检查动态验证码后重试。";
        return page("未能确认接手", "<p>" + message + "</p>");
    }

    private String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>%s</title>
                  <style>
                    body{margin:0;background:#f4f7f8;color:#172326;font-family:sans-serif}
                    main{max-width:34rem;margin:10vh auto;padding:2rem;background:#fff;
                         border-radius:1.25rem;box-shadow:0 .5rem 2rem #18343b1f}
                    h1{font-size:2rem;margin:0 0 1.5rem;color:#176278}
                    p,label,input,button{font-size:1.2rem;line-height:1.7}
                    label{display:block;font-weight:700;margin-top:1rem}
                    input{box-sizing:border-box;width:100%%;margin:.5rem 0 1.25rem;padding:.8rem;
                          border:.12rem solid #82979d;border-radius:.7rem;letter-spacing:.45rem}
                    button{width:100%%;padding:.8rem;border:0;border-radius:2rem;
                           background:#176278;color:#fff;font-weight:700}
                    .hint{color:#52676d}
                  </style>
                </head>
                <body><main><h1>%s</h1>%s</main></body>
                </html>
                """.formatted(title, title, body);
    }
}
