import { computed } from 'vue'
import { showToast } from 'vant'
import { getWechatAuthUrl } from '@/api/auth'

/**
 * 微信环境检测与微信能力 composable。
 *
 * 微信登录流程(公众号网页授权 snsapi_base 静默授权):
 * 1. wechatLogin():调用后端获取授权 URL,跳转到微信
 * 2. 微信回调 H5,URL query 带 code 参数
 * 3. Login.vue onMounted 检测 code,调用 wechatLoginByCode(code)
 * 4. 后端用 code 换 openid:
 *    - 已绑定 → 登录成功
 *    - 未绑定 → 返回 bindToken,前端弹窗输入手机号+密码,调用 wechatBind()
 *
 * 配置:后端需设置 WECHAT_APP_ID / WECHAT_APP_SECRET 环境变量。
 * 未配置时 wechatLogin() 提示"微信登录未配置"。
 */

/** 判断当前是否在微信浏览器中 */
const detectWechat = (): boolean => {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent.toLowerCase()
  return ua.indexOf('micromessenger') !== -1
}

export function useWechat() {
  const isWechat = computed<boolean>(() => detectWechat())

  /**
   * 发起微信登录:获取授权 URL 并跳转。
   * 微信会回调到当前 H5 地址(带 code 参数),由 Login.vue 处理回调。
   */
  const wechatLogin = async (): Promise<void> => {
    try {
      // redirect 设为 /login,微信回调后会回到登录页并带 code 参数
      const res = await getWechatAuthUrl('/login')
      if (res?.authUrl) {
        // 跳转到微信授权页(整页跳转,非 SPA 路由)
        window.location.href = res.authUrl
      } else {
        showToast('获取微信授权链接失败')
      }
    } catch {
      // 拦截器已提示错误(如后端未配置 WECHAT_APP_ID)
    }
  }

  return {
    isWechat,
    wechatLogin,
  }
}
