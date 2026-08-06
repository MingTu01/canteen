import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// Vant 全量引入(简单可靠,后续可优化为按需引入)
import Vant from 'vant'
import 'vant/lib/index.css'

// 全局样式
import './styles/global.scss'

// 图片本地缓存（IndexedDB）
import { cleanExpiredCache } from './utils/imageCache'
// 版本检测:微信浏览器缓存自动刷新
import { startVersionCheck } from './composables/useVersionCheck'
// H5 懒加载缓存：菜品图片 + 登录用户头像（用到才加载，加载后缓存）
// 启动时清理过期缓存
cleanExpiredCache()
// 启动版本检测(页面可见时 + 定时检测)
startVersionCheck()

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(Vant)

// 全局错误处理
app.config.errorHandler = (err) => {
  if (import.meta.env.DEV) console.error('[H5 App Error]', err)
}

app.mount('#app')

// 移除首屏 loading
const loading = document.getElementById('app-loading')
if (loading) {
  loading.style.opacity = '0'
  setTimeout(() => loading.remove(), 200)
}
