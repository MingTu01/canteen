/**
 * 摄像头后台扫码 composable(无感扫码)
 *
 * 使用 getUserMedia 打开 USB 摄像头,通过 @zxing/library 实时解码
 * QR 码和条形码。解码成功后调用回调,与读卡器输入统一处理。
 *
 * 设计:后台自动运行,无需用户点击。与读卡器并行工作,
 * 接受同一个防抖间隔(避免同一码在间隔内重复触发)。
 *
 * 支持 USB 摄像头热插拔:监听 devicechange 事件,设备变化时自动
 * 重新枚举并重启扫码;摄像头拔掉后 cameraCount 更新为 0,isScanning 变 false。
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
  options: { sameCodeCooldownMs?: number } = {},
) {
  /**
   * 同一码重复触发的冷却间隔(毫秒),不同码不受影响立即触发。
   * 修复 BUG:摄像头长时间空闲后 USB 选择性暂停/驱动休眠,视频流冻结在
   * 最后一帧,ZXing 反复解码出同一陈旧帧文本;或视野内有常驻二维码图案。
   * 原防抖(2-3 秒)过期后同码再次触发 onScan → 弹「请扫描H5支付码」
   * → 5 秒自动关闭 → 又触发,无限循环弹窗。同一码 30 秒内不再重复触发。
   */
  const sameCodeCooldownMs = options.sameCodeCooldownMs ?? 30000

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
  /** 摄像头是否可用(有设备且扫码中) */
  const cameraAvailable = ref(false)

  let reader: BrowserMultiFormatReader | null = null
  /** 上次解码的码 + 时间(防抖) */
  let lastCode = ''
  let lastCodeTime = 0
  /** 当前使用的 video 元素(热插拔重启时需要) */
  let activeVideoEl: HTMLVideoElement | null = null
  /** devicechange 事件处理函数引用(用于移除监听) */
  let deviceChangeHandler: (() => void) | null = null
  /** 热插拔重启防抖(避免短时间内多次 devicechange 触发重启) */
  let hotplugTimer: ReturnType<typeof setTimeout> | null = null
  /** 流健康监测定时器(检测 track 死亡/帧冻结并自动重启) */
  let healthTimer: ReturnType<typeof setInterval> | null = null
  /** 上次健康检查时的 video.currentTime(停滞说明流冻结) */
  let lastVideoTime = -1
  /** currentTime 连续停滞的检查次数 */
  let frozenChecks = 0
  /** 健康检查间隔(毫秒) */
  const HEALTH_CHECK_INTERVAL = 10000

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

  /** 仅枚举摄像头数量(不触发 getUserMedia 权限请求,用于热插拔检测) */
  const countCameras = async (): Promise<number> => {
    if (!isCameraSupported()) return 0
    try {
      const devices = await navigator.mediaDevices.enumerateDevices()
      return devices.filter((d) => d.kind === 'videoinput').length
    } catch {
      return 0
    }
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

    activeVideoEl = videoElement
    error.value = ''
    try {
      const devices = await enumerateCameras()
      cameraCount.value = devices.length
      if (devices.length === 0) {
        error.value = '未检测到摄像头设备'
        cameraAvailable.value = false
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
          // 防抖:不同码立即触发;同一码受 30 秒冷却保护
          // (防冻结帧/常驻码反复触发导致无限循环弹窗,见 sameCodeCooldownMs 注释)
          const now = Date.now()
          if (text === lastCode && now - lastCodeTime < sameCodeCooldownMs) return
          lastCode = text
          lastCodeTime = now
          onScan(text)
        },
      )
      isScanning.value = true
      cameraAvailable.value = true

      // 注册 devicechange 事件监听(热插拔)
      if (!deviceChangeHandler) {
        deviceChangeHandler = () => {
          // 防抖:500ms 内多次 devicechange 只处理一次
          if (hotplugTimer) clearTimeout(hotplugTimer)
          hotplugTimer = setTimeout(handleDeviceChange, 500)
        }
        navigator.mediaDevices.addEventListener('devicechange', deviceChangeHandler)
      }

      // 启动流健康监测(track 死亡/帧冻结自动重启)
      if (!healthTimer) {
        lastVideoTime = -1
        frozenChecks = 0
        healthTimer = setInterval(checkStreamHealth, HEALTH_CHECK_INTERVAL)
      }

      return true
    } catch (e: any) {
      error.value = e?.message || '摄像头启动失败'
      isScanning.value = false
      cameraAvailable.value = false
      return false
    }
  }

  /**
   * 重启摄像头扫码(流异常时自动恢复)。
   * 不重置 lastCode:重启后若视野内仍是同一常驻码,30 秒冷却继续生效,
   * 避免重启瞬间又弹一次错误提示。
   */
  const restartCamera = async () => {
    if (!activeVideoEl) return
    if (reader) {
      try {
        reader.stopContinuousDecode()
        reader.reset()
      } catch {
        /* 忽略 */
      }
    }
    isScanning.value = false
    cameraAvailable.value = false
    frozenChecks = 0
    lastVideoTime = -1
    await start(activeVideoEl)
  }

  /**
   * 流健康监测(修复摄像头长时间空闲后反复弹「请扫描H5支付码」的 BUG):
   * - track.readyState === 'ended':流已死亡(USB 选择性暂停/驱动休眠)→ 重启
   * - video.currentTime 连续 2 个周期无变化:帧冻结,ZXing 会反复解码
   *   同一陈旧帧导致同码反复触发 → 重启后画面恢复实时
   */
  const checkStreamHealth = () => {
    if (!isScanning.value || !activeVideoEl) return
    const video = activeVideoEl
    const stream = video.srcObject as MediaStream | null
    const track = stream?.getVideoTracks?.()[0]
    if (track && track.readyState === 'ended') {
      restartCamera()
      return
    }
    if (video.readyState >= 2 && video.currentTime === lastVideoTime) {
      frozenChecks++
      if (frozenChecks >= 2) {
        frozenChecks = 0
        restartCamera()
        return
      }
    } else {
      frozenChecks = 0
    }
    lastVideoTime = video.currentTime
  }

  /**
   * 处理 devicechange 事件(USB 摄像头插拔)。
   * 重新枚举设备:摄像头没了则停止扫码;有新摄像头则重启扫码。
   */
  const handleDeviceChange = async () => {
    const count = await countCameras()
    cameraCount.value = count

    if (count === 0) {
      // 摄像头被拔掉:停止扫码,更新状态
      if (reader) {
        try {
          reader.stopContinuousDecode()
          reader.reset()
        } catch {
          /* 忽略 */
        }
      }
      isScanning.value = false
      cameraAvailable.value = false
      cameras.value = []
      currentDeviceId.value = ''
      return
    }

    // 有摄像头但当前未扫码:尝试重启扫码(可能是重新插入)
    if (count > 0 && !isScanning.value && activeVideoEl) {
      // 先停止旧的 reader 状态再重启
      if (reader) {
        try {
          reader.stopContinuousDecode()
          reader.reset()
        } catch {
          /* 忽略 */
        }
      }
      await start(activeVideoEl)
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
    cameraAvailable.value = false
    // 移除 devicechange 监听
    if (deviceChangeHandler) {
      try {
        navigator.mediaDevices.removeEventListener('devicechange', deviceChangeHandler)
      } catch {
        /* 忽略 */
      }
      deviceChangeHandler = null
    }
    if (hotplugTimer) {
      clearTimeout(hotplugTimer)
      hotplugTimer = null
    }
    // 停止流健康监测
    if (healthTimer) {
      clearInterval(healthTimer)
      healthTimer = null
    }
    frozenChecks = 0
    lastVideoTime = -1
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
    cameraAvailable,
    start,
    stop,
    enumerateCameras,
  }
}
