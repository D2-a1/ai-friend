package com.aifriend.wxapi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aifriend.BuildConfig
import com.aifriend.feature.auth.WechatLoginCallbackBroker
import com.aifriend.feature.auth.WechatLoginCallbackOutcome
import com.aifriend.feature.wechat.WechatMessageOpenSdkCallbackBroker
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 微信 Open SDK 回调入口；只接收登录授权结果，不展示或持久化授权码。 */
@AndroidEntryPoint
class WXEntryActivity : ComponentActivity(), IWXAPIEventHandler {

    @Inject
    lateinit var callbackBroker: WechatLoginCallbackBroker

    @Inject
    lateinit var messageCallbackBroker: WechatMessageOpenSdkCallbackBroker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWechatIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWechatIntent(intent)
    }

    override fun onReq(request: BaseReq?) {
        finish()
    }

    override fun onResp(response: BaseResp?) {
        if (response?.type == ConstantsAPI.COMMAND_SENDMESSAGE_TO_WX) {
            messageCallbackBroker.complete(response.transaction, response.errCode)
            finish()
            return
        }
        val authResponse = response as? SendAuth.Resp
        val outcome = when {
            response?.type != ConstantsAPI.COMMAND_SENDAUTH -> WechatLoginCallbackOutcome.FAILED
            response.errCode == BaseResp.ErrCode.ERR_OK -> WechatLoginCallbackOutcome.AUTHORIZED
            response.errCode == BaseResp.ErrCode.ERR_USER_CANCEL ->
                WechatLoginCallbackOutcome.CANCELLED
            response.errCode == BaseResp.ErrCode.ERR_AUTH_DENIED -> WechatLoginCallbackOutcome.DENIED
            else -> WechatLoginCallbackOutcome.FAILED
        }
        callbackBroker.complete(authResponse?.state, authResponse?.code, outcome)
        finish()
    }

    private fun handleWechatIntent(intent: Intent?) {
        if (BuildConfig.WECHAT_APP_ID.isBlank()) {
            callbackBroker.clear()
            finish()
            return
        }
        val handled = runCatching {
            WXAPIFactory.createWXAPI(this, BuildConfig.WECHAT_APP_ID, true)
                .handleIntent(intent, this)
        }.getOrDefault(false)
        if (!handled) {
            callbackBroker.complete(null, null, WechatLoginCallbackOutcome.FAILED)
            finish()
        }
    }
}
