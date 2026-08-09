import { createApp } from 'vue'
import router from './router'
import './style.css'
import App from './App.vue'
import { cleanExpiredCache as cleanAvatarCache } from './utils/imageCache'
import { initBrandingFromCache } from './store/branding'

// 启动时清理过期头像缓存
cleanAvatarCache()

/**
 * 从本地缓存恢复品牌数据(不请求网络),让背景在应用挂载前就准备好。
 * 这样软件一打开,背景图片已经是本地 IndexedDB 的 blob URL,加载极快,
 * 页面切换时背景实例不卸载,彻底消除闪烁/黑屏。
 * 后续 App.vue onMounted 会调用 fetchBranding({ background: true }) 异步校验更新。
 */
initBrandingFromCache()

/**
 * 全局错误兜底:7x24 终端任意未捕获错误不得导致白屏无响应。
 * - errorHandler:捕获 Vue 组件渲染/生命周期错误
 * - window error:捕获脚本/资源加载错误
 * - unhandledrejection:捕获未处理 Promise rejection
 * 全部仅记录日志,不抛错,避免连锁崩溃。
 */
const logError = (kind: string, err: unknown) => {
  try {
    const msg = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
    console.error(`[global ${kind}]`, msg)
  } catch {
    /* 日志失败本身不能再抛错 */
  }
}

const app = createApp(App)
app.config.errorHandler = (err, _instance, info) => {
  logError('vue', err)
  if (typeof info === 'string' && info.length > 0) {
    console.error('[vue info]', info)
  }
}

window.addEventListener('error', (e) => {
  logError('error', e.error ?? e.message)
  // 资源加载错误不阻止默认行为,脚本错误也只记录,避免页面无出口
  e.preventDefault()
})

window.addEventListener('unhandledrejection', (e) => {
  logError('promise', e.reason)
  e.preventDefault()
})

app.use(router)
app.mount('#app')