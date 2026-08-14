<script setup lang="ts">
/**
 * 终端配置页(管理界面)
 *
 * 功能:
 * - 未绑定:输入服务器域名 + 管理员账号 + 食堂安全码完成绑定
 * - 已绑定:展示绑定信息、切换运行模式(订餐机/取餐机)、解除绑定
 * - 系统信息展示(版本、UA、分辨率)
 * - 敏感操作(绑定/解绑)要求管理员密码二次验证
 *
 * 设计:
 * - 使用 TopBar + 卡片 + BigButton 统一组件
 * - 全部 scoped CSS,引用 --doubao-* 设计令牌
 * - 触摸目标 ≥ 56px,字号引用 --fs-* 流式变量
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { version as appVersion } from '../../package.json'
import {
  Server,
  RotateCcw,
  Info,
  ShieldCheck,
  Loader2,
  CheckCircle2,
  ShoppingCart,
  ClipboardList,
  Monitor,
  CreditCard,
  Timer,
  Camera,
  Usb,
  RefreshCw,
  CheckCircle,
  XCircle,
} from 'lucide-vue-next'
import { loadConfig, bindTerminal, clearConfig, saveConfig, type TerminalConfig } from '@/api'
import { clearBranding } from '@/store/branding'
import { destroyLocalCache } from '@/utils/cache'
import { destroyEmployeeCache } from '@/utils/employeeCache'
import { getServerUrl, setRuntimeConfig, getDeviceStatus, restartCardReader, type CardReaderStatus } from '@/api/shellApi'
import {
  loadRuntimeConfig,
  windowMode,
  cardInterval,
  idleTimeoutSeconds,
  isPythonShell,
} from '@/store/terminalSettings'
import { isCameraSupported } from '@/composables/useCameraScanner'
import TopBar from '@/components/TopBar.vue'

const router = useRouter()

// ===== 当前绑定状态 =====
const boundConfig = ref<TerminalConfig | null>(null)

// ===== 绑定表单 =====
// serverUrl 留空 = 同源(开发模式走 vite proxy);生产部署填绝对地址如 https://canteen.xxx.com
const form = ref({
  serverUrl: '',
  username: '',
  password: '',
  securityCode: '',
  deviceLabel: '',
  mode: 'order' as 'order' | 'pickup',
})

const binding = ref(false)
const bindError = ref('')
const bindSuccess = ref(false)
let bindSuccessTimer: ReturnType<typeof setTimeout> | null = null

// 探测状态:用于按钮文案与提示
const probing = ref(false)
const probeMessage = ref('')

// ===== 解绑(需管理员密码二次校验,防止未授权人员物理接触后解绑) =====
const unbindConfirmVisible = ref(false)
const unbindVerifying = ref(false)
const unbindVerifyError = ref('')
// 二次校验:管理员用户名 + 密码(调用 /admin/login 验证,不依赖本地保存的凭据)
const unbindForm = ref({ username: '', password: '' })

// ===== 终端运行设置(仅 Python Shell 环境可用) =====
// 本地表单副本,编辑时用;保存时同步到 store + Python config.json
const runtimeForm = ref({
  windowMode: 'fullscreen' as 'fullscreen' | 'windowed',
  cardInterval: 2.0,
  idleTimeout: 30,
})
const runtimeSaving = ref(false)
const runtimeMsg = ref<{ type: 'success' | 'error' | 'info'; text: string } | null>(null)
let runtimeMsgTimer: ReturnType<typeof setTimeout> | null = null

// ===== 设备状态检查(读卡器 + 摄像头/扫码枪) =====
/** 读卡器状态(null = 未检测) */
const cardReaderStatus = ref<CardReaderStatus | null>(null)
/** 摄像头设备列表 */
const cameraDevices = ref<{ label: string; deviceId: string }[]>([])
/** 设备检测中 */
const deviceChecking = ref(false)
/** 读卡器重启中 */
const cardReaderRestarting = ref(false)
/** 摄像头是否支持 */
const cameraSupported = isCameraSupported()

/**
 * 检测读卡器状态(Python Shell 环境)。
 * 通过 /__api__/device_status 端点获取 card_reader.status_info()。
 */
const checkCardReader = async () => {
  if (!isPythonShell.value) {
    cardReaderStatus.value = null
    return
  }
  try {
    const result = await getDeviceStatus()
    cardReaderStatus.value = result?.cardReader ?? null
  } catch {
    cardReaderStatus.value = null
  }
}

/**
 * 检测摄像头/扫码枪设备。
 * 使用 navigator.mediaDevices.enumerateDevices() 枚举视频输入设备。
 * 需要先获得 getUserMedia 权限才能拿到设备 label。
 */
const checkCameras = async () => {
  if (!cameraSupported) {
    cameraDevices.value = []
    return
  }
  try {
    // 先请求权限,这样才能拿到设备 label
    const stream = await navigator.mediaDevices.getUserMedia({ video: true })
    stream.getTracks().forEach((t) => t.stop())
    const devices = await navigator.mediaDevices.enumerateDevices()
    cameraDevices.value = devices
      .filter((d) => d.kind === 'videoinput')
      .map((d) => ({
        label: d.label || `摄像头 ${d.deviceId.slice(0, 8)}`,
        deviceId: d.deviceId,
      }))
  } catch {
    // 权限被拒绝或无摄像头
    cameraDevices.value = []
  }
}

/** 一键检查所有设备 */
const checkAllDevices = async () => {
  deviceChecking.value = true
  try {
    await Promise.all([checkCardReader(), checkCameras()])
  } finally {
    deviceChecking.value = false
  }
}

/** 重启读卡器 */
const onRestartCardReader = async () => {
  cardReaderRestarting.value = true
  try {
    await restartCardReader()
    // 重启后重新检测状态
    await new Promise((r) => setTimeout(r, 1000))
    await checkCardReader()
  } finally {
    cardReaderRestarting.value = false
  }
}

/** 读卡器状态文字 */
const cardReaderStatusText = (): string => {
  if (!isPythonShell.value) return '浏览器环境(不支持检测)'
  if (!cardReaderStatus.value) return '未检测'
  if (cardReaderStatus.value.connected) return '已连接(读卡助手 HID 模式)'
  if (!cardReaderStatus.value.connected) return '读卡助手未运行(请先启动读卡助手)'
  return '未连接'
}

/** 读卡器是否正常 */
const cardReaderOk = (): boolean => {
  return cardReaderStatus.value?.connected ?? false
}

/** 从 store 同步到本地表单(进入页面/保存后调用) */
function syncRuntimeForm() {
  runtimeForm.value.windowMode = windowMode.value
  runtimeForm.value.cardInterval = cardInterval.value
  runtimeForm.value.idleTimeout = idleTimeoutSeconds.value
}

/**
 * 保存运行设置到 Python config.json(静默保存,仅失败时提示)。
 * 由 goRun 在"进入运行模式"时自动调用,无需独立保存按钮。
 * @returns 是否保存成功
 */
async function saveRuntimeConfig(): Promise<boolean> {
  if (!isPythonShell.value) return true
  runtimeSaving.value = true
  runtimeMsg.value = null
  try {
    const updates = {
      window_mode: runtimeForm.value.windowMode,
      card_interval: Number(runtimeForm.value.cardInterval),
      idle_timeout: Number(runtimeForm.value.idleTimeout),
    }
    const ok = await setRuntimeConfig(updates)
    if (ok) {
      // 重新从 Python 读取,同步 store(保持单一数据源)
      await loadRuntimeConfig()
      syncRuntimeForm()
      // window_mode 的动态切换由 Python 端 on_config_updated 信号处理:
      // set_config 写入后 emit config_updated → main.py 直接调用 switch_to_fullscreen_mode / switch_to_config_mode
    } else {
      runtimeMsg.value = { type: 'error', text: '保存失败,请检查配置文件权限' }
    }
    return ok
  } catch (e: any) {
    runtimeMsg.value = { type: 'error', text: e?.message || '保存失败' }
    return false
  } finally {
    runtimeSaving.value = false
    if (runtimeMsgTimer) clearTimeout(runtimeMsgTimer)
    runtimeMsgTimer = setTimeout(() => { runtimeMsg.value = null }, 4000)
  }
}

const reloadBound = () => {
  boundConfig.value = loadConfig()
  if (boundConfig.value) {
    // 已绑定时,同步表单默认值(方便重新绑定)
    form.value.serverUrl = boundConfig.value.serverUrl
    form.value.deviceLabel = boundConfig.value.deviceLabel
    form.value.mode = boundConfig.value.mode
  }
}

/**
 * 自动探测后端端口。
 * 用户输入 "http://192.168.10.79" 时,自动尝试常见端口找到后端。
 * 探测用 no-cors fetch:只要 TCP 连通就认为端口可用(opaque 响应也 resolve)。
 *
 * @param baseUrl 用户输入的基础地址
 * @returns 探测到的完整 URL(含端口);无需探测或探测失败返回 null
 */
async function probeBackendPort(baseUrl: string): Promise<string | null> {
  let parsed: URL
  try {
    // 允许不带协议的输入,如 "192.168.10.79"
    if (!/^https?:\/\//i.test(baseUrl)) {
      baseUrl = 'http://' + baseUrl
    }
    parsed = new URL(baseUrl)
  } catch {
    return null
  }

  const protocol = parsed.protocol
  const hostname = parsed.hostname

  // 已显式带端口 → 不探测
  if (parsed.port) return null

  // 候选端口:按可能性排序
  const candidates = protocol === 'https:'
    ? [443, 8443, 8080, 9080, 9000]
    : [8080, 80, 8443, 8888, 9090, 3000, 5000, 81, 82, 83, 8081, 8082, 9000]

  // 探测单个端口:2 秒超时,no-cors 模式只检测连通性
  const probe = (port: number): Promise<number | null> => {
    return new Promise((resolve) => {
      const controller = new AbortController()
      const timer = setTimeout(() => {
        controller.abort()
        resolve(null)
      }, 2000)
      fetch(`${protocol}//${hostname}:${port}/api/system/health`, {
        mode: 'no-cors',
        signal: controller.signal,
        cache: 'no-cache',
      }).then(() => {
        clearTimeout(timer)
        resolve(port)
      }).catch(() => {
        clearTimeout(timer)
        resolve(null)
      })
    })
  }

  // 并行探测,第一个成功即返回
  return new Promise((resolve) => {
    let settled = false
    let remaining = candidates.length
    candidates.forEach(port => {
      probe(port).then(result => {
        if (settled) return
        if (result !== null) {
          settled = true
          // 默认端口不显式写出
          if ((protocol === 'http:' && result === 80) ||
              (protocol === 'https:' && result === 443)) {
            resolve(`${protocol}//${hostname}`)
          } else {
            resolve(`${protocol}//${hostname}:${result}`)
          }
        } else {
          remaining--
          if (remaining === 0) resolve(null)
        }
      })
    })
  })
}

const doBind = async () => {
  bindError.value = ''
  // serverUrl 允许留空(同源开发模式)
  if (!form.value.username.trim() || !form.value.password) {
    bindError.value = '请输入管理员账号和密码'
    return
  }
  if (!form.value.securityCode.trim()) {
    bindError.value = '请输入食堂安全码'
    return
  }

  binding.value = true

  // 自动探测端口:用户输入的地址没带端口时,尝试常见端口
  let finalServerUrl = form.value.serverUrl.trim()
  if (finalServerUrl && !/^https?:\/\/[^\s/]+:\d+/i.test(finalServerUrl)) {
    probing.value = true
    probeMessage.value = '正在探测服务器端口...'
    try {
      const detected = await probeBackendPort(finalServerUrl)
      if (detected) {
        finalServerUrl = detected
        form.value.serverUrl = detected // 回填到表单,用户可见
        probeMessage.value = ''
      } else {
        probing.value = false
        bindError.value = '无法连接服务器,请检查地址或手动指定端口(如 :8080)'
        binding.value = false
        return
      }
    } catch {
      probing.value = false
      // 探测失败不阻断,继续用原地址尝试绑定(让后端给出明确错误)
    }
    probing.value = false
  }

  try {
    await bindTerminal({
      serverUrl: finalServerUrl,
      username: form.value.username.trim(),
      password: form.value.password,
      securityCode: form.value.securityCode.trim(),
      deviceLabel: form.value.deviceLabel.trim(),
      mode: form.value.mode,
    })
    bindSuccess.value = true
    reloadBound()
    bindSuccessTimer = setTimeout(() => {
      bindSuccess.value = false
      // 绑定成功后直接进入对应模式
      goRun()
    }, 800)
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '绑定失败'
    bindError.value = msg
  } finally {
    binding.value = false
  }
}

/**
 * 解绑:先调用 /admin/login 验证管理员密码,通过后再清除本地配置。
 * 复用登录接口避免新增后端接口;验证时使用配置中的 serverUrl。
 */
const doUnbind = async () => {
  if (unbindVerifying.value) return
  // 前端基础校验
  if (!unbindForm.value.username.trim() || !unbindForm.value.password) {
    unbindVerifyError.value = '请输入管理员账号和密码'
    return
  }
  unbindVerifying.value = true
  unbindVerifyError.value = ''
  try {
    // 调用 /admin/login 验证(复用现有接口,不引入新后端接口)
    const base = (boundConfig.value?.serverUrl || '').replace(/\/$/, '')
    const url = base ? `${base}/api/admin/login` : '/api/admin/login'
    // 动态 import 避免污染全局 api 实例
    const axios = (await import('axios')).default
    const resp = await axios.post(url, {
      username: unbindForm.value.username.trim(),
      password: unbindForm.value.password,
    })
    if (resp.data?.code !== 200) {
      unbindVerifyError.value = resp.data?.message || '管理员账号或密码错误'
      return
    }
    // 验证通过,执行解绑
    clearConfig()
    clearBranding()
    // 销毁缓存管理器:停止 SSE/轮询定时器,避免解绑后继续发请求(P1-6)
    destroyLocalCache()
    destroyEmployeeCache().catch(() => {})
    reloadBound()
    unbindConfirmVisible.value = false
    // 清空表单
    unbindForm.value = { username: '', password: '' }
  } catch (e: any) {
    unbindVerifyError.value = e?.response?.data?.message || '验证失败,请检查网络或账号密码'
  } finally {
    unbindVerifying.value = false
  }
}

/** 打开解绑弹窗时清空表单 */
const openUnbindModal = () => {
  unbindForm.value = { username: '', password: '' }
  unbindVerifyError.value = ''
  unbindConfirmVisible.value = true
}

/** 切换运行模式(只改本地配置,不重新绑定) */
const switchMode = (mode: 'order' | 'pickup') => {
  if (!boundConfig.value) return
  boundConfig.value.mode = mode
  saveConfig(boundConfig.value)
}

/**
 * 进入运行模式:先自动保存终端运行设置(Python Shell 环境),
 * 保存成功后根据当前 mode 跳转。保存失败则停留在配置页提示错误。
 */
const goRun = async () => {
  // Python Shell 环境下先保存运行设置(窗口模式/读卡间隔/无操作超时)
  if (isPythonShell.value) {
    const ok = await saveRuntimeConfig()
    if (!ok) return // 保存失败不跳转,让用户看到错误提示
  }
  const cfg = loadConfig()
  if (!cfg) return
  if (cfg.mode === 'pickup') {
    router.push('/pickup')
  } else {
    router.push('/order')
  }
}

const formatBoundTime = (iso: string): string => {
  try {
    const d = new Date(iso)
    const pad = (x: number) => String(x).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return iso
  }
}

// 暴露给模板使用的全局对象
const userAgent = navigator.userAgent
const screenWidth = window.screen.width
const screenHeight = window.screen.height

/**
 * 从 shell config.json 读取预设服务器地址,自动填入表单。
 * 仅在未绑定时填充,避免覆盖已绑定配置的展示。
 * 浏览器开发模式下返回空,静默忽略。
 */
async function prefillServerUrl() {
  if (boundConfig.value) return // 已绑定不覆盖
  const url = await getServerUrl()
  if (url) {
    form.value.serverUrl = url
  }
}

/** 设备状态轮询定时器 */
let devicePollTimer: ReturnType<typeof setInterval> | null = null
/** devicechange 事件处理函数引用 */
let deviceChangeHandler: (() => void) | null = null

onMounted(async () => {
  reloadBound()
  await prefillServerUrl()
  // 主动加载运行时配置(不等 App.vue 的异步加载,确保 isPythonShell 及时更新)
  await loadRuntimeConfig().catch(() => {})
  syncRuntimeForm()
  // 自动检测设备状态
  checkAllDevices()

  // 读卡器状态轮询(每 3 秒,Python Shell 环境)
  // 确保读卡器拔掉后状态实时更新(card_reader.py 的 connected 基于 idr_read 返回码)
  if (isPythonShell.value) {
    devicePollTimer = setInterval(checkCardReader, 3000)
  }

  // 摄像头热插拔监听(devicechange 事件)
  // 拔掉/插入摄像头时自动重新枚举,状态实时更新
  if (cameraSupported && navigator.mediaDevices) {
    deviceChangeHandler = () => {
      // 延迟 500ms 等设备枚举稳定
      setTimeout(checkCameras, 500)
    }
    navigator.mediaDevices.addEventListener('devicechange', deviceChangeHandler)
  }
})
onBeforeUnmount(() => {
  if (bindSuccessTimer) clearTimeout(bindSuccessTimer)
  if (runtimeMsgTimer) clearTimeout(runtimeMsgTimer)
  // 清理设备状态轮询
  if (devicePollTimer) {
    clearInterval(devicePollTimer)
    devicePollTimer = null
  }
  // 清理 devicechange 监听
  if (deviceChangeHandler && navigator.mediaDevices) {
    navigator.mediaDevices.removeEventListener('devicechange', deviceChangeHandler)
    deviceChangeHandler = null
  }
})
</script>

<template>
  <main class="settings">
    <TopBar title="终端配置" :show-back="false" />

    <div class="settings__body no-scrollbar">
      <div class="settings__container">
        <!-- 已绑定:展示绑定信息 + 模式切换 + 解绑 -->
        <template v-if="boundConfig">
          <!-- 绑定信息卡 -->
          <section class="card settings__bound-card">
            <header class="card__header">
              <h2 class="card__title">
                <ShieldCheck :size="22" class="card__icon card__icon--success" />
                已绑定食堂
              </h2>
              <span class="settings__badge">在线</span>
            </header>
            <dl class="settings__info-list">
              <div class="settings__info-row">
                <dt>服务器</dt>
                <dd class="text-ellipsis">{{ boundConfig.serverUrl || '同源(开发模式)' }}</dd>
              </div>
              <div class="settings__info-row">
                <dt>食堂</dt>
                <dd>{{ boundConfig.storeName }} (#{{ boundConfig.storeId }})</dd>
              </div>
              <div class="settings__info-row">
                <dt>设备标识</dt>
                <dd>{{ boundConfig.deviceLabel || '—' }}</dd>
              </div>
              <div class="settings__info-row">
                <dt>绑定时间</dt>
                <dd>{{ formatBoundTime(boundConfig.boundAt) }}</dd>
              </div>
            </dl>
          </section>

          <!-- 模式切换卡 -->
          <section class="card">
            <header class="card__header">
              <h2 class="card__title">
                <Server :size="22" class="card__icon card__icon--primary" />
                运行模式
              </h2>
              <span class="card__hint">切换后立即生效</span>
            </header>
            <div class="settings__mode-grid">
              <button
                class="mode-tile btn-press"
                :class="{ 'mode-tile--active': boundConfig.mode === 'order' }"
                @click="switchMode('order')"
              >
                <ShoppingCart :size="32" class="mode-tile__icon" />
                <span class="mode-tile__name">订餐机</span>
                <span class="mode-tile__desc">员工刷卡/选菜下单</span>
              </button>
              <button
                class="mode-tile btn-press"
                :class="{ 'mode-tile--active': boundConfig.mode === 'pickup' }"
                @click="switchMode('pickup')"
              >
                <ClipboardList :size="32" class="mode-tile__icon" />
                <span class="mode-tile__name">取餐机</span>
                <span class="mode-tile__desc">输码核销取餐</span>
              </button>
            </div>
          </section>

          <!-- 终端运行设置卡(仅 Python Shell 环境) -->
          <section v-if="isPythonShell" class="card">
            <header class="card__header">
              <h2 class="card__title">
                <Monitor :size="22" class="card__icon card__icon--primary" />
                终端运行设置
              </h2>
              <span class="card__hint">进入运行模式时自动保存</span>
            </header>

            <div class="settings__runtime-form">
              <!-- 窗口模式 -->
              <div class="settings__field">
                <label class="settings__label">
                  <Monitor :size="14" />
                  窗口模式
                </label>
                <div class="settings__seg-group">
                  <button
                    class="seg-btn btn-press"
                    :class="{ 'seg-btn--active': runtimeForm.windowMode === 'fullscreen' }"
                    @click="runtimeForm.windowMode = 'fullscreen'"
                  >
                    全屏无边框
                  </button>
                  <button
                    class="seg-btn btn-press"
                    :class="{ 'seg-btn--active': runtimeForm.windowMode === 'windowed' }"
                    @click="runtimeForm.windowMode = 'windowed'"
                  >
                    窗口模式
                  </button>
                </div>
                <p class="settings__field-hint">全屏适合终端设备;窗口模式(1280×800)适合调试。进入运行模式时切换生效。</p>
              </div>

              <!-- 读卡间隔 -->
              <div class="settings__field">
                <label class="settings__label" for="cfg-card-interval">
                  <CreditCard :size="14" />
                  读卡防抖间隔(秒)
                </label>
                <input
                  id="cfg-card-interval"
                  v-model.number="runtimeForm.cardInterval"
                  type="number"
                  min="0.5"
                  max="10"
                  step="0.5"
                  class="settings__input"
                />
                <p class="settings__field-hint">同一张卡在此间隔内不重复触发,避免一次刷卡多次响应。推荐 1.0~3.0 秒。</p>
              </div>

              <!-- 菜品显示时间(自动返回待机) -->
              <div class="settings__field">
                <label class="settings__label" for="cfg-idle-timeout">
                  <Timer :size="14" />
                  无操作返回待机时间(秒)
                </label>
                <input
                  id="cfg-idle-timeout"
                  v-model.number="runtimeForm.idleTimeout"
                  type="number"
                  min="0"
                  max="3600"
                  step="10"
                  class="settings__input"
                />
                <p class="settings__field-hint">用户在选菜/取餐页面无操作超过此时间后自动返回待机页。0 表示永不自动返回。</p>
              </div>

              <!-- 错误提示(仅保存失败时显示) -->
              <div
                v-if="runtimeMsg && runtimeMsg.type === 'error'"
                class="settings__alert settings__alert--error"
              >
                <Info :size="18" class="settings__alert-icon" />
                <span>{{ runtimeMsg.text }}</span>
              </div>

              <p class="settings__field-hint settings__field-hint--save-tip">
                设置在点击"进入运行模式"时自动保存并生效。
              </p>
            </div>
          </section>

          <!-- 设备状态检查卡 -->
          <section class="card">
            <header class="card__header">
              <h2 class="card__title">
                <Usb :size="22" class="card__icon card__icon--primary" />
                设备连接状态
              </h2>
              <button
                class="settings__refresh-btn btn-press"
                :disabled="deviceChecking"
                @click="checkAllDevices"
              >
                <Loader2 v-if="deviceChecking" class="spinner" :size="14" />
                <RefreshCw v-else :size="14" />
                <span>{{ deviceChecking ? '检测中...' : '刷新' }}</span>
              </button>
            </header>

            <div class="settings__device-list">
              <!-- 读卡器状态 -->
              <div class="settings__device-item">
                <div class="settings__device-icon" :class="cardReaderOk() ? 'settings__device-icon--ok' : 'settings__device-icon--err'">
                  <CheckCircle v-if="cardReaderOk()" :size="20" />
                  <XCircle v-else :size="20" />
                </div>
                <div class="settings__device-info">
                  <div class="settings__device-name">
                    <CreditCard :size="14" />
                    <span>读卡器</span>
                    <span
                      class="settings__device-badge"
                      :class="cardReaderOk() ? 'settings__device-badge--ok' : 'settings__device-badge--err'"
                    >
                      {{ cardReaderStatusText() }}
                    </span>
                  </div>
                  <div class="settings__device-desc">
                    <template v-if="cardReaderStatus">
                      {{ cardReaderStatus.description }} · 防抖 {{ cardReaderStatus.interval }}秒
                    </template>
                    <template v-else-if="!isPythonShell">
                      浏览器环境不支持读卡器检测,请在终端 Python Shell 环境下使用
                    </template>
                    <template v-else>
                      点击"刷新"重新检测
                    </template>
                  </div>
                  <!-- 读卡器异常时显示重连按钮(重新连接读卡助手) -->
                  <button
                    v-if="isPythonShell && !cardReaderOk()"
                    class="settings__device-action btn-press"
                    :disabled="cardReaderRestarting"
                    @click="onRestartCardReader"
                  >
                    <Loader2 v-if="cardReaderRestarting" class="spinner" :size="14" />
                    <RotateCcw v-else :size="14" />
                    <span>{{ cardReaderRestarting ? '重连中...' : '重新连接读卡助手' }}</span>
                  </button>
                </div>
              </div>

              <!-- 摄像头/扫码枪状态 -->
              <div class="settings__device-item">
                <div class="settings__device-icon" :class="cameraDevices.length > 0 ? 'settings__device-icon--ok' : 'settings__device-icon--err'">
                  <CheckCircle v-if="cameraDevices.length > 0" :size="20" />
                  <XCircle v-else :size="20" />
                </div>
                <div class="settings__device-info">
                  <div class="settings__device-name">
                    <Camera :size="14" />
                    <span>摄像头/扫码枪</span>
                    <span
                      class="settings__device-badge"
                      :class="cameraDevices.length > 0 ? 'settings__device-badge--ok' : 'settings__device-badge--err'"
                    >
                      {{ cameraDevices.length > 0 ? `已连接(${cameraDevices.length}个)` : '未检测到' }}
                    </span>
                  </div>
                  <div v-if="cameraDevices.length > 0" class="settings__device-desc">
                    <div v-for="cam in cameraDevices" :key="cam.deviceId" class="settings__device-sub">
                      · {{ cam.label }}
                    </div>
                  </div>
                  <div v-else class="settings__device-desc">
                    <template v-if="!cameraSupported">浏览器不支持摄像头 API</template>
                    <template v-else>未检测到摄像头设备,或权限被拒绝。USB 扫码枪(键盘模拟)不需要摄像头,可正常使用。</template>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <!-- 主操作按钮(自动保存运行设置后跳转) -->
          <button
            class="settings__primary-btn btn-press"
            :disabled="runtimeSaving"
            @click="goRun"
          >
            <Loader2 v-if="runtimeSaving" class="spinner" :size="22" />
            <CheckCircle2 v-else :size="22" />
            <span>{{ runtimeSaving ? '保存并进入...' : '进入运行模式' }}</span>
          </button>

          <!-- 解绑按钮 -->
          <button
            class="settings__danger-btn btn-press"
            @click="openUnbindModal"
          >
            <RotateCcw :size="18" />
            <span>解除绑定(重新配置)</span>
          </button>
        </template>

        <!-- 未绑定:绑定表单 -->
        <template v-else>
          <!-- 服务器配置 -->
          <section class="card">
            <header class="card__header">
              <h2 class="card__title">
                <Server :size="22" class="card__icon card__icon--primary" />
                服务器配置
              </h2>
            </header>
            <p class="card__desc">
              填入服务器地址、管理员账号密码、食堂安全码完成绑定。绑定后终端将被锁定到该食堂,无法越权访问其他门店。
            </p>
            <div class="settings__form">
              <div class="settings__field">
                <label class="settings__label">服务器地址(留空=同源开发模式,可省略端口自动探测)</label>
                <input
                  v-model="form.serverUrl"
                  type="text"
                  class="settings__input"
                  placeholder="如 http://192.168.10.79 或 https://canteen.xxx.com (端口可省略)"
                />
              </div>
              <div class="settings__field-row">
                <div class="settings__field">
                  <label class="settings__label">管理员账号</label>
                  <input
                    v-model="form.username"
                    type="text"
                    autocomplete="off"
                    class="settings__input"
                    placeholder="超管或本店管理员账号"
                  />
                </div>
                <div class="settings__field">
                  <label class="settings__label">管理员密码</label>
                  <input
                    v-model="form.password"
                    type="password"
                    autocomplete="off"
                    class="settings__input"
                  />
                </div>
              </div>
              <div class="settings__field">
                <label class="settings__label">食堂安全码</label>
                <input
                  v-model="form.securityCode"
                  type="text"
                  class="settings__input settings__input--code"
                  placeholder="8 位安全码(向超管索取)"
                />
              </div>
              <div class="settings__field">
                <label class="settings__label">设备标识(可选)</label>
                <input
                  v-model="form.deviceLabel"
                  type="text"
                  class="settings__input"
                  placeholder="如:前台订餐机"
                />
              </div>
            </div>
          </section>

          <!-- 模式选择 -->
          <section class="card">
            <header class="card__header">
              <h2 class="card__title">
                <Server :size="22" class="card__icon card__icon--primary" />
                选择运行模式
              </h2>
            </header>
            <div class="settings__mode-grid">
              <button
                class="mode-tile btn-press"
                :class="{ 'mode-tile--active': form.mode === 'order' }"
                @click="form.mode = 'order'"
              >
                <ShoppingCart :size="32" class="mode-tile__icon" />
                <span class="mode-tile__name">订餐机</span>
                <span class="mode-tile__desc">员工刷卡/选菜下单</span>
              </button>
              <button
                class="mode-tile btn-press"
                :class="{ 'mode-tile--active': form.mode === 'pickup' }"
                @click="form.mode = 'pickup'"
              >
                <ClipboardList :size="32" class="mode-tile__icon" />
                <span class="mode-tile__name">取餐机</span>
                <span class="mode-tile__desc">输码核销取餐</span>
              </button>
            </div>
          </section>

          <!-- 错误提示 -->
          <div v-if="bindError" class="settings__alert settings__alert--error">
            <Info :size="18" class="settings__alert-icon" />
            <span>{{ bindError }}</span>
          </div>
          <!-- 探测中提示 -->
          <div v-if="probing" class="settings__alert settings__alert--info">
            <Loader2 :size="18" class="spinner" />
            <span>{{ probeMessage || '正在探测服务器端口...' }}</span>
          </div>
          <!-- 成功提示 -->
          <div v-if="bindSuccess" class="settings__alert settings__alert--success">
            <CheckCircle2 :size="18" class="settings__alert-icon" />
            <span>绑定成功,即将进入运行模式...</span>
          </div>

          <!-- 绑定按钮 -->
          <button
            class="settings__primary-btn btn-press"
            :disabled="binding || probing"
            @click="doBind"
          >
            <Loader2 v-if="binding || probing" class="spinner" :size="22" />
            <Save v-else :size="22" />
            <span>{{ probing ? '探测中...' : (binding ? '绑定中...' : '测试并绑定') }}</span>
          </button>
        </template>

        <!-- 系统信息 -->
        <section class="card">
          <header class="card__header">
            <h2 class="card__title">
              <Info :size="22" class="card__icon card__icon--primary" />
              系统信息
            </h2>
          </header>
          <dl class="settings__info-list">
            <div class="settings__info-row">
              <dt>系统版本</dt>
              <dd>v{{ appVersion }}</dd>
            </div>
            <div class="settings__info-row">
              <dt>浏览器</dt>
              <dd class="text-ellipsis">{{ userAgent.split(' ').slice(-1)[0] }}</dd>
            </div>
            <div class="settings__info-row">
              <dt>屏幕分辨率</dt>
              <dd>{{ screenWidth }} x {{ screenHeight }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>

    <!-- 解绑确认弹窗(需管理员密码二次校验) -->
    <div
      v-if="unbindConfirmVisible"
      class="modal"
      @click.self="!unbindVerifying && (unbindConfirmVisible = false)"
    >
      <div class="modal__panel">
        <h3 class="modal__title">确认解除绑定?</h3>
        <p class="modal__desc">
          解绑后本机将清除食堂绑定信息,需重新绑定才能使用。为防止误操作,请输入管理员账号密码确认(超管或本店管理员均可)。
        </p>
        <div class="modal__form">
          <input
            v-model="unbindForm.username"
            type="text"
            placeholder="超管或本店管理员账号"
            autocomplete="off"
            :disabled="unbindVerifying"
            class="modal__input"
          />
          <input
            v-model="unbindForm.password"
            type="password"
            placeholder="管理员密码"
            autocomplete="off"
            :disabled="unbindVerifying"
            class="modal__input"
            @keyup.enter="doUnbind"
          />
          <div v-if="unbindVerifyError" class="modal__error">{{ unbindVerifyError }}</div>
        </div>
        <div class="modal__actions">
          <button
            class="modal__btn modal__btn--ghost btn-press"
            :disabled="unbindVerifying"
            @click="unbindConfirmVisible = false"
          >
            取消
          </button>
          <button
            class="modal__btn modal__btn--danger btn-press"
            :disabled="unbindVerifying"
            @click="doUnbind"
          >
            <Loader2 v-if="unbindVerifying" class="spinner" :size="16" />
            {{ unbindVerifying ? '验证中...' : '确认解绑' }}
          </button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--doubao-background);
}
.settings__body {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: 24px;
}
.settings__container {
  max-width: 640px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 通用卡片(覆盖全局 .card 以适配终端布局) */
.card {
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  padding: 24px;
  overflow: hidden;
}
.card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.card__title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.card__icon { flex-shrink: 0; }
.card__icon--primary { color: var(--doubao-primary); }
.card__icon--success { color: var(--doubao-success); }
.card__hint {
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}
.card__desc {
  margin: 0 0 16px;
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
  line-height: 1.6;
}

/* 已绑定卡片 */
.settings__bound-card {
  background: var(--doubao-secondary);
}
.settings__badge {
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(7, 193, 96, 0.12);
  color: var(--doubao-success);
  font-size: var(--fs-xs);
  font-weight: 700;
}

/* 信息列表 */
.settings__info-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.settings__info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  font-size: var(--fs-sm);
}
.settings__info-row dt {
  color: var(--doubao-muted-foreground);
  flex-shrink: 0;
}
.settings__info-row dd {
  margin: 0;
  color: var(--doubao-foreground);
  text-align: right;
  min-width: 0;
  max-width: 60%;
}

/* 模式选择网格 */
.settings__mode-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.mode-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 16px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: 2px solid transparent;
  color: var(--doubao-foreground);
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.16s ease, background 0.16s ease;
}
.mode-tile__icon {
  color: var(--doubao-muted-foreground);
  transition: color 0.16s ease;
}
.mode-tile__name {
  font-size: var(--fs-xl);
  font-weight: 700;
}
.mode-tile__desc {
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}
.mode-tile--active {
  border-color: var(--doubao-primary);
  background: var(--doubao-accent);
}
.mode-tile--active .mode-tile__icon,
.mode-tile--active .mode-tile__name {
  color: var(--doubao-primary);
}

/* 表单 */
.settings__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.settings__field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.settings__field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.settings__label {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.settings__input {
  width: 100%;
  min-width: 0;
  padding: 14px 16px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: 1.5px solid transparent;
  color: var(--doubao-foreground);
  font-size: var(--fs-base);
  font-family: inherit;
  outline: none;
  /* X86 终端禁用 background 过渡:灰 -> 白切换会触发整片背景重绘闪烁 */
  transition: border-color 0.16s ease;
}
.settings__input:focus {
  border-color: var(--doubao-primary);
}
.settings__input--code {
  letter-spacing: 4px;
  font-variant-numeric: tabular-nums;
}

/* 主按钮 */
.settings__primary-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: var(--touch-lg);
  padding: 0 24px;
  border: none;
  border-radius: var(--doubao-radius);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-lg);
  font-weight: 700;
  cursor: pointer;
  font-family: inherit;
}
.settings__primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 危险按钮(解绑) */
.settings__danger-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: var(--touch-md);
  padding: 0 24px;
  border: 1px solid rgba(239, 68, 68, 0.4);
  border-radius: var(--doubao-radius);
  background: transparent;
  color: var(--doubao-destructive);
  font-size: var(--fs-sm);
  font-weight: 400;
  cursor: pointer;
  font-family: inherit;
}

/* 提示条 */
.settings__alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-radius: var(--doubao-radius-sm);
  font-size: var(--fs-sm);
}
.settings__alert--error {
  background: rgba(239, 68, 68, 0.08);
  color: var(--doubao-destructive);
}
.settings__alert--success {
  background: rgba(7, 193, 96, 0.08);
  color: var(--doubao-success);
}
.settings__alert--info {
  background: rgba(59, 130, 246, 0.08);
  color: #2563eb;
}
.settings__alert-icon { flex-shrink: 0; }

/* ============ 终端运行设置表单 ============ */
.settings__runtime-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.settings__field-hint {
  margin-top: 6px;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  line-height: 1.4;
}
/* 分段按钮(窗口模式切换) */
.settings__seg-group {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.seg-btn {
  flex: 1;
  padding: 14px 12px;
  border-radius: var(--doubao-radius-sm);
  border: 1.5px solid var(--doubao-border);
  background: var(--doubao-card);
  color: var(--doubao-secondary-foreground);
  font-size: var(--fs-base);
  font-weight: 400;
  cursor: pointer;
  transition: all 0.15s ease;
}
.seg-btn:hover {
  border-color: var(--doubao-ring);
}
.seg-btn--active {
  border-color: var(--doubao-primary);
  background: rgba(0, 101, 253, 0.06);
  color: var(--doubao-primary);
  font-weight: 700;
}
/* 运行设置保存按钮 */
.settings__runtime-save {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  align-self: flex-start;
  padding: 12px 24px;
  border-radius: var(--doubao-radius-sm);
  border: none;
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-base);
  font-weight: 700;
  cursor: pointer;
  transition: opacity 0.15s ease;
}
.settings__runtime-save:active {
  opacity: 0.85;
}
.settings__runtime-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ============ 设备状态检查 ============ */
.settings__refresh-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--doubao-border);
  background: var(--doubao-card);
  color: var(--doubao-text-secondary);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: all 0.2s ease;
}
.settings__refresh-btn:hover:not(:disabled) {
  border-color: var(--doubao-primary);
  color: var(--doubao-primary);
}
.settings__refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.settings__device-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.settings__device-item {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: var(--doubao-radius);
  background: var(--doubao-bg-secondary, #f5f5f7);
}
.settings__device-icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.settings__device-icon--ok {
  background: rgba(7, 193, 96, 0.12);
  color: #07c160;
}
.settings__device-icon--err {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}
.settings__device-info {
  flex: 1;
  min-width: 0;
}
.settings__device-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--doubao-text, #1d1d1f);
}
.settings__device-badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: var(--fs-xs);
  font-weight: 500;
}
.settings__device-badge--ok {
  background: rgba(7, 193, 96, 0.12);
  color: #07c160;
}
.settings__device-badge--err {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}
.settings__device-desc {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--doubao-text-secondary, #86868b);
  line-height: 1.5;
}
.settings__device-sub {
  font-size: var(--fs-sm);
  color: var(--doubao-text-secondary, #86868b);
}
.settings__device-action {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--doubao-primary, #007aff);
  background: transparent;
  color: var(--doubao-primary, #007aff);
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: all 0.2s ease;
}
.settings__device-action:hover:not(:disabled) {
  background: var(--doubao-primary, #007aff);
  color: #fff;
}
.settings__device-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 模态框 */
.modal {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(14, 17, 21, 0.5);
}
.modal__panel {
  width: 100%;
  max-width: 420px;
  padding: 28px 24px;
  background: var(--doubao-card);
  border: 1px solid var(--doubao-border);
  border-radius: var(--doubao-radius);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
}
.modal__panel--sm {
  max-width: 340px;
}
.modal__title {
  margin: 0 0 12px;
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.modal__desc {
  margin: 0 0 20px;
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
  line-height: 1.6;
}
.modal__actions {
  display: flex;
  gap: 12px;
}
/* 解绑弹窗的密码输入表单 */
.modal__form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 16px 0;
}
.modal__input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: 1px solid var(--doubao-border);
  color: var(--doubao-foreground);
  font-size: var(--fs-base);
  font-family: inherit;
}
.modal__input:focus {
  outline: 2px solid var(--doubao-primary);
  outline-offset: -1px;
}
.modal__input:disabled { opacity: 0.5; }
.modal__error {
  padding: 8px 12px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-destructive-light, rgba(239, 68, 68, 0.1));
  color: var(--doubao-destructive);
  font-size: var(--fs-sm);
}
.modal__btn {
  flex: 1;
  min-height: var(--touch-md);
  padding: 0 16px;
  border-radius: var(--doubao-radius-sm);
  border: none;
  font-size: var(--fs-base);
  font-weight: 700;
  cursor: pointer;
  font-family: inherit;
}
.modal__btn--ghost {
  background: var(--doubao-secondary);
  color: var(--doubao-foreground);
}
.modal__btn--primary {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.modal__btn--danger {
  background: var(--doubao-destructive);
  color: #fff;
}

/* 低分辨率横屏(720p)紧凑布局 */
@media (max-width: 1366px) and (orientation: landscape) {
  .settings__body { padding: 16px; }
  .card { padding: 18px; }
  .mode-tile { padding: 16px 12px; }
}

/* 竖屏适配 */
@media (orientation: portrait) {
  .settings__field-row {
    grid-template-columns: 1fr;
  }
  .settings__container {
    max-width: 480px;
  }
}
</style>
