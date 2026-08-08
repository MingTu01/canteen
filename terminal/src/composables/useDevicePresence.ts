/**
 * 设备在线检测 composable
 *
 * 统一检测读卡器、摄像头的在线状态，提供动态提示文字和图标。
 * 读卡器:Python Shell 环境通过 /__api__/device_status 轮询(每 3 秒),
 *         基于最近一次 idr_read 返回码判断真实硬件连接(拔掉后返回码变为 22/23)。
 * 摄像头:由 useCameraScanner 的 cameraAvailable 响应式驱动(支持 devicechange 热插拔)。
 * 扫码枪:USB HID 键盘模拟设备,浏览器无法区分于普通键盘,默认视为可用。
 *
 * 提示文字规则:
 *   读卡器 + 摄像头/扫码枪 → "请刷卡或扫码取餐"
 *   只有读卡器              → "请刷卡取餐"
 *   只有摄像头/扫码枪       → "请扫码取餐"
 *   都没有                  → 默认"请刷卡或扫码取餐"(扫码枪可能存在但无法检测)
 */
import { ref, computed, onMounted, onUnmounted, type Ref } from 'vue'
import { detectShell, getDeviceStatus, type CardReaderStatus } from '@/api/shellApi'

export interface DevicePresence {
  /** 读卡器是否在线(Python Shell 环境真实检测) */
  hasCardReader: Ref<boolean>
  /** 摄像头是否可用(由 useCameraScanner 驱动) */
  hasCamera: Ref<boolean>
  /** 设备检测中(Python Shell 环境) */
  checking: Ref<boolean>
  /** 读卡器详细状态 */
  cardReaderStatus: Ref<CardReaderStatus | null>
  /** 刷新读卡器状态(手动触发) */
  refreshCardReader: () => Promise<void>
}

/**
 * 使用设备在线检测。
 * @param cameraAvailable 摄像头可用状态(来自 useCameraScanner 的 cameraAvailable ref)
 * @returns 设备状态和提示信息
 */
export function useDevicePresence(
  cameraAvailable: Ref<boolean>,
): DevicePresence {
  const isPythonShell = detectShell() === 'python'

  /** 读卡器状态(null = 非 Python Shell 或未检测) */
  const cardReaderStatus = ref<CardReaderStatus | null>(null)
  /** 设备检测中 */
  const checking = ref(false)
  /** 轮询定时器 */
  let pollTimer: ReturnType<typeof setInterval> | null = null
  /** 轮询间隔(毫秒) */
  const POLL_INTERVAL = 3000

  /** 读卡器是否在线(基于真实硬件返回码判断) */
  const hasCardReader = computed(() => {
    // 非 Python Shell 环境:无法检测读卡器,默认视为可用
    // (USB HID 键盘模拟读卡器无需 Python Shell)
    if (!isPythonShell) return false
    return !!cardReaderStatus.value?.connected
  })

  /** 摄像头是否可用(由外部传入) */
  const hasCamera = computed(() => cameraAvailable.value)

  /** 拉取读卡器状态 */
  const fetchCardReaderStatus = async () => {
    if (!isPythonShell) return
    try {
      const result = await getDeviceStatus()
      cardReaderStatus.value = result?.cardReader ?? null
    } catch {
      cardReaderStatus.value = null
    }
  }

  /** 手动刷新读卡器状态 */
  const refreshCardReader = async () => {
    checking.value = true
    try {
      await fetchCardReaderStatus()
    } finally {
      checking.value = false
    }
  }

  onMounted(() => {
    if (isPythonShell) {
      fetchCardReaderStatus()
      pollTimer = setInterval(fetchCardReaderStatus, POLL_INTERVAL)
    }
  })

  onUnmounted(() => {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  })

  return {
    hasCardReader,
    hasCamera,
    checking,
    cardReaderStatus,
    refreshCardReader,
  }
}

/**
 * 根据设备在线状态生成待机页提示文字。
 * @param hasCardReader 读卡器是否在线
 * @param hasCamera 摄像头是否可用
 * @param isPickup 是否取餐页(取餐页和订餐页文案略有不同)
 * @returns 提示文字
 */
export function getScanHint(
  hasCardReader: boolean,
  hasCamera: boolean,
  isPickup: boolean = true,
): string {
  if (hasCardReader && hasCamera) {
    // 都有:都提示
    return isPickup ? '请刷卡或扫码取餐' : '请刷卡或扫码'
  }
  if (hasCardReader && !hasCamera) {
    // 只有读卡器
    return isPickup ? '请刷卡取餐' : '请刷卡'
  }
  if (!hasCardReader && hasCamera) {
    // 只有摄像头/扫码枪
    return isPickup ? '请扫码取餐' : '请扫码'
  }
  // 都没有:默认提示(扫码枪可能存在但无法检测)
  return isPickup ? '请刷卡或扫码取餐' : '请刷卡或扫码'
}
