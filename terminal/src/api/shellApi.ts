/**
 * Shell API 统一封装 - 支持 Tauri / Python Shell / 浏览器三种环境。
 *
 * 检测顺序:
 * 1. Tauri:window.__TAURI_INTERNALS__ 存在
 * 2. Python Shell:window.__pythonShell === true(Python 启动时注入)
 * 3. 浏览器:开发模式
 *
 * 前端 → Python 的调用通过 fetch /__api__/xxx(本地 HTTP 服务器端点)。
 * Python → 前端的卡号推送通过 window.__onCardRead(由 useCardReader.ts 监听)。
 */

type ShellType = 'tauri' | 'python' | 'browser'

/** 终端运行配置(Python config.json) */
export interface TerminalRuntimeConfig {
  /** 服务器地址 */
  server_url: string
  /** 窗口模式:fullscreen=全屏 / windowed=窗口 */
  window_mode: 'fullscreen' | 'windowed'
  /** 读卡防抖间隔(秒) */
  card_interval: number
  /** 无操作自动返回待机页时间(秒,0=永不) */
  idle_timeout: number
}

/** 检测当前 shell 环境 */
export function detectShell(): ShellType {
  if (typeof window !== 'undefined') {
    const w = window as any
    if (w.__TAURI_INTERNALS__ || w.__TAURI__) return 'tauri'
    if (w.__pythonShell === true) return 'python'
  }
  return 'browser'
}

/**
 * 获取预设服务器地址(从 config.json 读取)。
 * 终端绑定页面会调用此函数预填服务器地址,方便批量部署。
 */
export async function getServerUrl(): Promise<string> {
  const shell = detectShell()
  if (shell === 'tauri') {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      return await invoke<string>('get_server_url')
    } catch {
      return ''
    }
  }
  if (shell === 'python') {
    try {
      const res = await fetch('/__api__/server_url')
      const data = await res.json()
      return data.server_url || ''
    } catch {
      return ''
    }
  }
  return ''
}

/**
 * 获取终端运行配置(window_mode/card_interval/idle_timeout)。
 * 浏览器/Tauri 环境返回 null(不支持)。
 */
export async function getRuntimeConfig(): Promise<TerminalRuntimeConfig | null> {
  const shell = detectShell()
  if (shell !== 'python') return null
  try {
    const res = await fetch('/__api__/config')
    const data = await res.json()
    if (data.ok && data.config) return data.config as TerminalRuntimeConfig
    return null
  } catch {
    return null
  }
}

/**
 * 更新终端运行配置(部分字段,写入 config.json)。
 * card_interval 立即生效;window_mode 需重启应用生效;idle_timeout 前端自行读取生效。
 * @returns 是否成功
 */
export async function setRuntimeConfig(updates: Partial<TerminalRuntimeConfig>): Promise<boolean> {
  const shell = detectShell()
  if (shell !== 'python') return false
  try {
    const res = await fetch('/__api__/set_config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updates),
    })
    const data = await res.json()
    return !!data.ok
  } catch {
    return false
  }
}

/**
 * 切换到配置模式(取消全屏,显示标题栏)。
 * 管理员入口验证密码后调用。
 */
export async function switchToConfigMode(): Promise<void> {
  const shell = detectShell()
  if (shell === 'tauri') {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('switch_to_config_mode')
    } catch (e) {
      console.error('[shellApi] switchToConfigMode 失败:', e)
    }
  } else if (shell === 'python') {
    try {
      await fetch('/__api__/switch_to_config', { method: 'POST' })
    } catch (e) {
      console.error('[shellApi] switchToConfigMode 失败:', e)
    }
  }
}

/**
 * 通用 Shell 调用(Python 环境):POST /__api__/{method}
 * 用于 switch_to_fullscreen 等无需返回值的端点。
 */
export async function callShell(method: string): Promise<void> {
  const shell = detectShell()
  if (shell !== 'python') return
  try {
    await fetch(`/__api__/${method}`, { method: 'POST' })
  } catch (e) {
    console.error(`[shellApi] callShell(${method}) 失败:`, e)
  }
}

/**
 * 退出应用。
 * 管理理入口的"退出"按钮调用。
 */
export async function quitApp(): Promise<void> {
  const shell = detectShell()
  if (shell === 'tauri') {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('quit_app')
    } catch (e) {
      console.error('[shellApi] quitApp 失败:', e)
    }
  } else if (shell === 'python') {
    try {
      await fetch('/__api__/quit', { method: 'POST' })
    } catch (e) {
      console.error('[shellApi] quitApp 失败:', e)
    }
  } else {
    // 浏览器环境:尝试关闭窗口
    window.close()
  }
}

/**
 * 重启读卡器(设置页可调用)。
 * @returns 读卡器是否成功启动
 */
export async function restartCardReader(): Promise<boolean> {
  const shell = detectShell()
  if (shell === 'tauri') {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      return await invoke<boolean>('restart_card_reader')
    } catch {
      return false
    }
  }
  if (shell === 'python') {
    try {
      const res = await fetch('/__api__/restart_card_reader', { method: 'POST' })
      const data = await res.json()
      return data.running || false
    } catch {
      return false
    }
  }
  return false
}
