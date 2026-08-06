/**
 * 版本检测:解决微信/浏览器缓存问题
 *
 * 原理:
 * - 构建时生成 version.json(含版本号和时间戳)
 * - 前端启动时拉取 version.json?t=时间戳(绕过缓存),与 localStorage 中的版本比较
 * - 版本不一致 → 刷新页面(location.reload)
 * - 页面从后台切回前台(visibilitychange)时也检测
 * - 每 5 分钟定时检测
 */

const VERSION_KEY = '__admin_app_version__'

/** 拉取 version.json 并比较版本 */
async function checkVersion(): Promise<void> {
  try {
    const res = await fetch(`/version.json?t=${Date.now()}`, {
      cache: 'no-store',
    })
    if (!res.ok) return
    const data = await res.json()
    const newVersion: string = data.version || ''
    if (!newVersion) return

    const oldVersion = localStorage.getItem(VERSION_KEY)
    if (oldVersion && oldVersion !== newVersion) {
      localStorage.setItem(VERSION_KEY, newVersion)
      window.location.reload()
      return
    }
    if (!oldVersion) {
      localStorage.setItem(VERSION_KEY, newVersion)
    }
  } catch {
    // 静默忽略
  }
}

/** 启动版本检测 */
export function startVersionCheck(): void {
  void checkVersion()

  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void checkVersion()
    }
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  setInterval(() => {
    void checkVersion()
  }, 5 * 60 * 1000)
}
