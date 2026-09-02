package com.aifriend.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 第一批身份与授权链路使用的稳定错误码。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum ErrorCode {
    /** 当前 Android Keystore 设备公钥未进入服务器白名单，或设备证明无效。 */
    DEVICE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "这台手机尚未放行"),
    /** 运维动态验证码无效、过期或已被使用。 */
    OPERATIONS_CREDENTIAL_INVALID(HttpStatus.UNAUTHORIZED, "动态验证码无效或已使用"),
    /** 未登录、令牌无效或令牌 family 已撤销。 */
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "请重新登录"),
    /** 账号注销已经可靠受理，旧账号不能继续使用。 */
    ACCOUNT_CLOSURE_ACCEPTED(HttpStatus.CONFLICT, "注销申请已经受理"),
    /** 客户端提交的状态或幂等语义与服务端当前状态冲突。 */
    SESSION_CONFLICT(HttpStatus.CONFLICT, "请求状态已变化，请刷新后重试"),
    /** 请求超过当前主体或接口的速率限制。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "操作太频繁，请稍后再试"),
    /** 请求字段、格式或约束校验失败。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    /** 邀请、等待验证绑定与有效绑定数量达到上限。 */
    CONTACT_LIMIT_REACHED(HttpStatus.CONFLICT, "亲友或邀请数量已达上限"),
    /** 当前微信页面、好友关系或稳定定位证据无法唯一确认目标联系人。 */
    WECHAT_PAGE_UNVERIFIED(HttpStatus.UNPROCESSABLE_ENTITY, "无法确认当前联系人，请停止操作"),
    /** 当前微信版本与本机验证规则组合未进入服务端白名单。 */
    WECHAT_RULE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "当前微信版本暂不支持本机验证"),
    /** 公共邀请不存在、秘密错误、已使用、已撤销、已拒绝或已过期。 */
    INVITATION_UNAVAILABLE(HttpStatus.GONE, "邀请已失效，请联系邀请人重新发送"),
    /** 资源不存在或不属于当前主体。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "没有找到该记录"),
    /** 当前主体无权执行目标操作。 */
    FORBIDDEN(HttpStatus.FORBIDDEN, "当前账号无权执行此操作"),
    /** 缺少当前音频用途所需的分项授权。 */
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "请先完成当前语音用途授权"),
    /** 音频凭证、元数据或上传内容不符合安全约束。 */
    AUDIO_INVALID(HttpStatus.BAD_REQUEST, "音频无效，请重新录制"),
    /** 同类双录不一致，或安全指令之间无法可靠区分。 */
    ENROLLMENT_INCONSISTENT(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "注册录音不一致或彼此无法区分，请重新录制"),
    /** 新称呼与当前 owner 的有效称呼相同或高度相似。 */
    ALIAS_PHONETIC_CONFLICT(HttpStatus.CONFLICT, "这个称呼与已有称呼太像，请更换"),
    /** 新称呼相似度位于校准阈值边界。 */
    ALIAS_PHONETIC_BORDERLINE(HttpStatus.UNPROCESSABLE_ENTITY, "称呼区分度不足，请重新录制或更换"),
    /** 单联系人五个或单 owner 一百个有效称呼上限已满。 */
    ALIAS_LIMIT_REACHED(HttpStatus.CONFLICT, "称呼数量已达上限"),
    /** 联系人尚未完成本机验证或当前处于重新验证状态。 */
    LOCAL_VERIFICATION_REQUIRED(HttpStatus.CONFLICT, "请先完成联系人本机验证"),
    /** 方言包、模板模型或阈值版本未加载或不兼容。 */
    TEMPLATE_INCOMPATIBLE(HttpStatus.CONFLICT, "语音模板暂不可用，请稍后重新录入"),
    /** 当前 owner 尚未完整注册四类安全指令。 */
    SAFETY_COMMAND_REQUIRED(HttpStatus.CONFLICT, "请先完成四类安全指令注册"),
    /** 主备语音识别能力均不可用。 */
    ASR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "现在听不清，请稍后再试"),
    /** 没有找到可安全唯一选择的联系人。 */
    NO_CONTACT_MATCH(HttpStatus.UNPROCESSABLE_ENTITY, "没找到这个称呼，请换一种说法"),
    /** 无法可靠切分需要发送的有效原声。 */
    AUDIO_SEGMENT_UNCERTAIN(HttpStatus.UNPROCESSABLE_ENTITY, "无法可靠提取要发送的原声，请重新说"),
    /** 任务会话或确认窗口已经过期。 */
    SESSION_EXPIRED(HttpStatus.GONE, "本次任务已结束，请重新说"),
    /** 当前有限动作、微信规则或上报结果不受支持。 */
    ACTION_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "当前动作暂不支持，请停止操作"),
    /** 未分类的服务端内部故障。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂不可用，请稍后再试");

    /** 对应的 HTTP 状态。 */
    private final HttpStatus httpStatus;
    /** 可安全展示给用户的默认提示。 */
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 获取 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /**
     * 获取默认用户提示。
     *
     * @return 默认提示
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}
