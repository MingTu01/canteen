/**
 * 终端运行时设置 store
 *
 * 从 Python config.json 读取(Python Shell 环境),浏览器环境用默认值。
 * App.vue 启动时调用 loadRuntimeConfig() 初始化。
 * Settings.vue 修改后调用 loadRuntimeConfig() 重新同步。
 *
 * 当前导出的运行时参数:
 * - idleTimeoutSeconds: 无操作自动返回待机页时间(秒),供 useIdleTimer 使用
 * - windowMode / cardInterval: 仅 Settings.vue 展示与修改,运行时由 Python 侧处理
 */
import { ref } from 'vue'
import { getRuntimeConfig, type TerminalRuntimeConfig } from '@/api/shellApi'

/** 无操作自动返回待机页时间(秒)。0 = 永不自动返回。默认 30 秒。 */
export const idleTimeoutSeconds = ref(30)

/** 窗口模式(展示用,实际由 Python 启动时读取) */
export const windowMode = ref<TerminalRuntimeConfig['window_mode']>('fullscreen')

/** 读卡防抖间隔(秒,展示用,实际由 Python 侧 card_reader 处理) */
export const cardInterval = ref(2.0)

/** 渲染模式(展示用,重启后由 Python 启动逻辑生效) */
export const gpuMode = ref<'auto' | 'software'>('auto')

/** 屏幕键盘模式(v-osk 指令读取,实时生效) */
export const oskMode = ref<'auto' | 'off'>('auto')

/** 是否为 Python Shell 环境(决定是否支持运行时配置) */
export const isPythonShell = ref(false)

let loaded = false

/**
 * 从 Python 读取运行时配置并更新 store。
 * 浏览器环境静默跳过(保留默认值)。
 */
export async function loadRuntimeConfig(): Promise<void> {
  const cfg = await getRuntimeConfig()
  if (!cfg) return
  loaded = true
  isPythonShell.value = true
  idleTimeoutSeconds.value = cfg.idle_timeout
  windowMode.value = cfg.window_mode
  cardInterval.value = cfg.card_interval
  if (cfg.gpu_mode === 'software' || cfg.gpu_mode === 'auto') gpuMode.value = cfg.gpu_mode
  if (cfg.osk_mode === 'off' || cfg.osk_mode === 'auto') oskMode.value = cfg.osk_mode
}

/** 是否已加载过运行时配置 */
export function isRuntimeConfigLoaded(): boolean {
  return loaded
}
