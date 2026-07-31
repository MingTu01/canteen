/**
 * 无操作超时 composable
 *
 * 提取自 OrderMenu / OrderSelect / OrderConfirm / OrderQuery 中重复的 idle 逻辑。
 * 在 onMounted 注册 click/keydown/touchstart 监听 + 定时检查,
 * 超过 timeout 无操作则触发回调(通常是重置流程并返回待机页)。
 *
 * 用法:
 *   // 使用配置文件中的 idle_timeout(推荐,默认行为)
 *   const { resetActivity } = useIdleTimer(() => {
 *     resetOrderFlow()
 *     router.replace('/order')
 *   })
 *
 *   // 显式指定超时(覆盖配置值,如 OrderMenu 的 10 秒)
 *   useIdleTimer(() => { ... }, 10_000, 1_000)
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { idleTimeoutSeconds } from '@/store/terminalSettings'

export function useIdleTimer(
  onTimeout: () => void,
  /** 超时毫秒数。不传或传 0 时从终端配置 idle_timeout 读取(秒→毫秒) */
  timeout?: number,
  checkInterval = 5000,
) {
  const lastActivity = ref(Date.now())
  let timer = 0

  /** 获取当前生效的超时(支持配置热更新:每次 check 都读最新值) */
  const getEffectiveTimeout = () => {
    if (timeout && timeout > 0) return timeout
    // 0 表示永不超时
    if (idleTimeoutSeconds.value <= 0) return Infinity
    return idleTimeoutSeconds.value * 1000
  }

  const resetActivity = () => {
    lastActivity.value = Date.now()
  }

  const check = () => {
    if (Date.now() - lastActivity.value >= getEffectiveTimeout()) {
      onTimeout()
    }
  }

  onMounted(() => {
    window.addEventListener('click', resetActivity)
    window.addEventListener('keydown', resetActivity)
    window.addEventListener('touchstart', resetActivity)
    timer = window.setInterval(check, checkInterval)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('click', resetActivity)
    window.removeEventListener('keydown', resetActivity)
    window.removeEventListener('touchstart', resetActivity)
    if (timer) clearInterval(timer)
  })

  return { resetActivity }
}
