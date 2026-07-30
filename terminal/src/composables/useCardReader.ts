/**
 * 读卡器统一管理 composable
 *
 * 支持三种读卡器输入来源:
 * 1. Python Shell(ctypes 调 ICUSB.DLL,通过 window.__onCardRead 推送卡号)
 * 2. Tauri 后端(Rust FFI 调 ICUSB.DLL,发送 "card-read" 事件)
 * 3. USB HID 键盘模拟读卡器(通过 keydown 监听,Enter 结束输入)
 *
 * 用法:
 *   const { onCardRead } = useCardReader()
 *   onCardRead((cardNo) => { ... })
 */
import { ref, onMounted, onUnmounted } from 'vue'

type CardReadHandler = (cardNo: string) => void

const handlers = ref<Set<CardReadHandler>>(new Set())
let tauriListenerReady = false
let tauriUnlisten: (() => void) | null = null
let pythonBridgeReady = false

/** 通知所有注册的 handler */
function notifyHandlers(cardNo: string) {
  if (!cardNo || typeof cardNo !== 'string') return
  handlers.value.forEach((h) => {
    try {
      h(cardNo.trim())
    } catch (e) {
      console.error('[CardReader] handler 执行出错:', e)
    }
  })
}

/**
 * 初始化 Python Shell 桥接:设置 window.__onCardRead 全局函数。
 * Python 读卡器读到卡号后通过 page.runJavaScript("window.__onCardRead('xxx')") 调用。
 * 全局只初始化一次。
 */
function initPythonBridge() {
  if (pythonBridgeReady) return
  pythonBridgeReady = true
  ;(window as any).__onCardRead = (cardNo: string) => {
    notifyHandlers(cardNo)
  }
  console.log('[CardReader] Python Shell 桥接已就绪 (window.__onCardRead)')
}

/**
 * 初始化 Tauri 事件监听(CH375 读卡器)。
 * 全局只初始化一次,多个组件共享同一个监听器。
 */
async function initTauriListener() {
  if (tauriListenerReady) return
  tauriListenerReady = true
  try {
    const { listen } = await import('@tauri-apps/api/event')
    tauriUnlisten = await listen<string>('card-read', (event) => {
      notifyHandlers(event.payload)
    })
    console.log('[CardReader] Tauri 事件监听已启动')
  } catch {
    // 非 Tauri 环境(浏览器/Python Shell):忽略,Python Shell 用 __onCardRead
  }
}

/**
 * 注册卡号读取回调。
 * 同时保留 USB HID 键盘模拟读卡器的 keydown 监听作为降级方案。
 *
 * @param handler 卡号读取回调
 * @param options.listenKeyboard 是否监听键盘输入(USB HID 读卡器),默认 true
 */
export function useCardReader(
  handler: CardReadHandler,
  options: { listenKeyboard?: boolean } = {},
) {
  const { listenKeyboard = true } = options

  // 键盘缓冲(USB HID 读卡器降级方案)
  let cardBuffer = ''
  let cardBufferTimer: ReturnType<typeof setTimeout> | null = null
  const CARD_INPUT_TIMEOUT = 80 // 读卡器单字符间隔通常 < 50ms

  const onKeyPress = (e: KeyboardEvent) => {
    // 输入框焦点时不拦截键盘
    const target = e.target as HTMLElement | null
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return

    if (e.key === 'Enter') {
      if (cardBuffer.length > 0) {
        const cardNo = cardBuffer
        cardBuffer = ''
        if (cardBufferTimer) {
          clearTimeout(cardBufferTimer)
          cardBufferTimer = null
        }
        handler(cardNo)
      }
      return
    }

    if (e.key.length === 1) {
      cardBuffer += e.key
      if (cardBufferTimer) clearTimeout(cardBufferTimer)
      cardBufferTimer = setTimeout(() => {
        cardBuffer = ''
        cardBufferTimer = null
      }, CARD_INPUT_TIMEOUT)
    }
  }

  onMounted(() => {
    // 注册 handler
    handlers.value.add(handler)

    // 初始化 Python Shell 桥接(window.__onCardRead)
    initPythonBridge()

    // 初始化 Tauri 事件监听(非 Tauri 环境自动跳过)
    initTauriListener()

    // 注册键盘监听(USB HID 读卡器降级)
    if (listenKeyboard) {
      window.addEventListener('keydown', onKeyPress)
    }
  })

  onUnmounted(() => {
    handlers.value.delete(handler)
    if (listenKeyboard) {
      window.removeEventListener('keydown', onKeyPress)
    }
    if (cardBufferTimer) clearTimeout(cardBufferTimer)
  })
}

/**
 * 全局清理(应用退出时调用)
 */
export async function destroyCardReader() {
  if (tauriUnlisten) {
    tauriUnlisten()
    tauriUnlisten = null
  }
  tauriListenerReady = false
  handlers.value.clear()
}
