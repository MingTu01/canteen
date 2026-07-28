/**
 * 无操作超时 composable
 *
 * 提取自 OrderMenu / OrderSelect / OrderConfirm / OrderQuery 中重复的 idle 逻辑。
 * 在 onMounted 注册 click/keydown/touchstart 监听 + 定时检查,
 * 超过 timeout 无操作则触发回调(通常是重置流程并返回待机页)。
 *
 * 用法:
 *   const { resetActivity } = useIdleTimer(() => {
 *     resetOrderFlow()
 *     router.replace('/order')
 *   }, 120_000)
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

export function useIdleTimer(
  onTimeout: () => void,
  timeout = 120_000,
  checkInterval = 5000,
) {
  const lastActivity = ref(Date.now())
  let timer = 0

  const resetActivity = () => {
    lastActivity.value = Date.now()
  }

  const check = () => {
    if (Date.now() - lastActivity.value >= timeout) {
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
