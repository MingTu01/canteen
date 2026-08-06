/**
 * 版本检测:解决微信浏览器缓存问题
 *
 * 原理:
 * - 构建时生成 version.json(含版本号和时间戳),Vite 会对 JS/CSS 文件名加 hash
 * - 前端启动时拉取 version.json?t=时间戳(绕过缓存),与 localStorage 中的版本比较
 * - 版本不一致 → 清除缓存并刷新页面(location.reload)
 * - 页面从后台切回前台(visibilitychange)时也检测一次
 * - 每 5 分钟定时检测一次
 */
import { onMounted, onUnmounted } from 'vue'

/** localStorage 存储版本号的 key */
const VERSION_KEY = '__app_version__'

/** 拉取 version.json 并比较版本 */
async function checkVersion(): Promise<void> {
  try {
    // 加时间戳绕过所有缓存(微信浏览器对 GET 请求缓存非常激进)
    const res = await fetch(`/version.json?t=${Date.now()}`, {
      cache: 'no-store',
    })
    if (!res.ok) return
    const data = await res.json()
    const newVersion: string = data.version || ''
    if (!newVersion) return

    const oldVersion = localStorage.getItem(VERSION_KEY)
    if (oldVersion && oldVersion !== newVersion) {
      // 版本变化:清除缓存并刷新
      localStorage.setItem(VERSION_KEY, newVersion)
      // 强制刷新(绕过缓存)
      window.location.reload()
      return
    }
    // 首次记录或版本一致
    if (!oldVersion) {
      localStorage.setItem(VERSION_KEY, newVersion)
    }
  } catch {
    // 网络错误等,静默忽略
  }
}

/** 定时器引用 */
let timer: ReturnType<typeof setInterval> | null = null

/** 启动版本检测 */
export function startVersionCheck(): void {
  // 启动时立即检测一次
  void checkVersion()

  // 页面从后台切回前台时检测
  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void checkVersion()
    }
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  // 每 5 分钟定时检测
  timer = setInterval(() => {
    void checkVersion()
  }, 5 * 60 * 1000)
}

/** 停止版本检测 */
export function stopVersionCheck(): void {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** Vue 组合式函数:在组件中使用,自动管理生命周期 */
export function useVersionCheck(): void {
  onMounted(() => {
    startVersionCheck()
  })
  onUnmounted(() => {
    stopVersionCheck()
  })
}
