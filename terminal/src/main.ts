import { createApp } from 'vue'
import router from './router'
import './style.css'
import App from './App.vue'

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