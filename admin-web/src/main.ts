import { createApp } from 'vue'
import type { Component } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'nprogress/nprogress.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { startVersionCheck } from './composables/useVersionCheck'

// 版本检测:浏览器缓存自动刷新
startVersionCheck()

const app = createApp(App)

const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 全局注册 Element Plus 图标
const icons = ElementPlusIconsVue as Record<string, Component>
for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

// 全局错误处理
app.config.errorHandler = (err) => {
  if (import.meta.env.DEV) console.error(err)
}

app.mount('#app')
