package com.aifriend.feature.auth

import android.content.Context
import com.aifriend.BuildConfig
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** 使用微信 Open SDK 发起一次性移动端授权，不读取好友或聊天内容。 */
@Singleton
class AndroidWechatLoginAdapter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callbackBroker: WechatLoginCallbackBroker,
) : WechatLoginPort {

    override val events = callbackBroker.events

    override fun launch(): WechatLoginLaunchResult {
        if (!BuildConfig.WECHAT_LOGIN_ENABLED || !BuildConfig.WECHAT_APP_ID.matches(APP_ID_PATTERN)) {
            return WechatLoginLaunchResult.Unavailable("当前安装包尚未配置微信登录")
        }
        val state = randomState()
        if (!callbackBroker.begin(state)) {
            return WechatLoginLaunchResult.Unavailable("微信登录正在处理中，请稍候")
        }
        return try {
            val api = WXAPIFactory.createWXAPI(context, BuildConfig.WECHAT_APP_ID, true)
            if (!api.registerApp(BuildConfig.WECHAT_APP_ID) || !api.isWXAppInstalled) {
                callbackBroker.cancel(state)
                WechatLoginLaunchResult.Unavailable("请先安装并登录微信")
            } else {
                val request = SendAuth.Req().apply {
                    scope = LOGIN_SCOPE
                    this.state = state
                }
                if (api.sendReq(request)) {
                    WechatLoginLaunchResult.Started
                } else {
                    callbackBroker.cancel(state)
                    WechatLoginLaunchResult.Unavailable("暂时无法打开微信，请稍后重试")
                }
            }
        } catch (_: RuntimeException) {
            callbackBroker.cancel(state)
            WechatLoginLaunchResult.Unavailable("暂时无法打开微信，请稍后重试")
        }
    }

    override fun clear() {
        callbackBroker.clear()
    }

    private fun randomState(): String = ByteArray(STATE_BYTES)
        .also(secureRandom::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val secureRandom = SecureRandom()
        val APP_ID_PATTERN = Regex("^wx[0-9a-f]{16}$")
        const val LOGIN_SCOPE = "snsapi_userinfo"
        const val STATE_BYTES = 16
    }
}
