/**
 * 摄像头后台扫码 composable(无感扫码)
 *
 * 使用 getUserMedia 打开 USB 摄像头,通过 @zxing/library 实时解码
 * QR 码和条形码。解码成功后调用回调,与读卡器输入统一处理。
 *
 * 设计:后台自动运行,无需用户点击。与读卡器并行工作,
 * 接受同一个防抖间隔(避免同一码在间隔内重复触发)。
 *
 * 用法:
 *   const { start, stop, isScanning, cameraCount } = useCameraScanner(
 *     (code) => { console.log('扫描到:', code) },
 *     { debounceMs: 2000 }
 *   )
 *   await start(videoElement)   // 打开摄像头开始后台扫码
 *   stop()                      // 停止扫码
 */
import { ref, onUnmounted } from 'vue'
import { BrowserMultiFormatReader } from '@zxing/library'

type ScanCallback = (code: string) => void

/** 可用摄像头设备 */
export interface CameraDevice {
  deviceId: string
  label: string
}

/** 是否支持摄像头扫码 */
export function isCameraSupported(): boolean {
  return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
}

export function useCameraScanner(
  onScan: ScanCallback,
  options: { debounceMs?: number } = {},
) {
  /** 防抖间隔(毫秒),同一码在此间隔内不重复触发 */
  const debounceMs = options.debounceMs ?? 2000

  /** 是否正在扫码 */
  const isScanning = ref(false)
  /** 可用摄像头列表 */
  const cameras = ref<CameraDevice[]>([])
  /** 当前使用的摄像头 deviceId */
  const currentDeviceId = ref<string>('')
  /** 错误信息 */
  const error = ref('')
  /** 检测到的摄像头数量 */
  const cameraCount = ref(0)

  let reader: BrowserMultiFormatReader | null = null
  /** 上次解码的码 + 时间(防抖) */
  let lastCode = ''
  let lastCodeTime = 0

  /** 枚举可用摄像头(需要先获得 getUserMedia 权限才能拿到 label) */
  const enumerateCameras = async (): Promise<MediaDeviceInfo[]> => {
    if (!isCameraSupported()) return []
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true })
      stream.getTracks().forEach((t) => t.stop())
      const devices = await navigator.mediaDevices.enumerateDevices()
      return devices.filter((d) => d.kind === 'videoinput')
    } catch {
      return []
    }
  }

  /** 初始化 reader(惰性,首次 start 时创建) */
  const ensureReader = () => {
    if (!reader) {
      reader = new BrowserMultiFormatReader()
    }
    return reader
  }

  /**
   * 开始后台扫码(无感,不需要用户交互)。
   * @param videoElement <video> 元素(可隐藏,仅用于 ZXing 解码)
   * @returns 是否成功启动
   */
  const start = async (
    videoElement: HTMLVideoElement | null,
  ): Promise<boolean> => {
    if (!isCameraSupported()) {
      error.value = '当前浏览器不支持摄像头扫码'
      return false
    }
    if (!videoElement) {
      error.value = '未找到视频元素'
      return false
    }
    if (isScanning.value) return true

    error.value = ''
    try {
      const devices = await enumerateCameras()
      cameraCount.value = devices.length
      if (devices.length === 0) {
        error.value = '未检测到摄像头设备'
        return false
      }
      cameras.value = devices.map((d) => ({
        deviceId: d.deviceId,
        label: d.label || `摄像头 ${d.deviceId.slice(0, 8)}`,
      }))

      // 优先选择后置/外置摄像头(终端通常用 USB 摄像头)
      const back = devices.find((d) =>
        /back|rear|environment|usb|external/i.test(d.label),
      )
      const selectedId = back?.deviceId || devices[0].deviceId
      currentDeviceId.value = selectedId

      const r = ensureReader()
      await r.decodeFromVideoDevice(
        selectedId,
        videoElement,
        (result, _err) => {
          if (!result) return
          const text = result.getText()
          if (!text) return
          // 防抖:同一码在 debounceMs 内不重复触发
          const now = Date.now()
          if (text === lastCode && now - lastCodeTime < debounceMs) return
          lastCode = text
          lastCodeTime = now
          onScan(text)
        },
      )
      isScanning.value = true
      return true
    } catch (e: any) {
      error.value = e?.message || '摄像头启动失败'
      isScanning.value = false
      return false
    }
  }

  /** 停止扫码 */
  const stop = () => {
    if (reader) {
      try {
        reader.stopContinuousDecode()
        reader.reset()
      } catch {
        /* 忽略停止时的异常 */
      }
    }
    isScanning.value = false
  }

  onUnmounted(() => {
    stop()
  })

  return {
    isScanning,
    cameras,
    currentDeviceId,
    error,
    cameraCount,
    start,
    stop,
    enumerateCameras,
  }
}
