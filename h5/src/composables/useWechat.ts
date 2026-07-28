import { computed } from 'vue'
import { showToast } from 'vant'

/**
 * 微信环境检测与微信能力 composable。
 *
 * 后续接入微信公众号时,在此扩展:
 * - wechatLogin:微信授权登录(wx.oauth2)
 * - wechatPay:JSAPI 支付(wx.chooseWXPay)
 * - wechatShare:自定义分享(wx.updateAppMessageShareData)
 */

/** 判断当前是否在微信浏览器中 */
const detectWechat = (): boolean => {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent.toLowerCase()
  return ua.indexOf('micromessenger') !== -1
}

export function useWechat() {
  const isWechat = computed<boolean>(() => detectWechat())

  /** 微信授权登录(占位,后续接入公众号时实现) */
  const wechatLogin = (): void => {
    showToast('微信登录即将上线')
  }

  /** 微信支付(占位) */
  const wechatPay = (): void => {
    showToast('微信支付即将上线')
  }

  /** 微信分享(占位,空函数,后续接入) */
  const wechatShare = (): void => {
    /* no-op */
  }

  return {
    isWechat,
    wechatLogin,
    wechatPay,
    wechatShare,
  }
}
