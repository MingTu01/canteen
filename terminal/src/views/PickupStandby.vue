<script setup lang="ts">
/**
 * 取餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - Logo + 食堂名 + 大时钟
 * - 刷卡/扫码提示(USB 读卡器和扫码枪作为键盘设备,Enter 结束输入)
 *   - 先尝试刷卡(员工接口),失败再尝试支付码/身份二维码
 *
 * 生产环境:仅支持 USB 读卡器/扫码枪(键盘模拟输入)
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { getEmployeeByCardNo } from '@/utils/employeeCache'
import { pickupStore, resetPickupFlow } from '@/store/pickup'
import { brandingState, fetchBranding } from '@/store/branding'
import { fullDateLabel, pad2 } from '@/utils'
import { CreditCard, Loader2, Camera, ScanLine, Search } from 'lucide-vue-next'

import Modal from '@/components/Modal.vue'
import OrderQueryModal from '@/components/OrderQueryModal.vue'
import { useCardReader } from '@/composables/useCardReader'
import { useCameraScanner, isCameraSupported } from '@/composables/useCameraScanner'
import { useDevicePresence, getScanHint } from '@/composables/useDevicePresence'
import { cardInterval } from '@/store/terminalSettings'

const router = useRouter()
const clock = ref('')
const dateLabel = ref('')
const scanning = ref(false)

const branding = computed(() => brandingState.data)
const storeName = computed(() => branding.value?.name || '企业智慧食堂')

/* 错误弹窗(统一 Modal) */
const showError = ref(false)
const errorTitle = ref('')
const errorMsg = ref('')
/** 错误弹窗 5 秒自动消失定时器 */
let errorTimer: ReturnType<typeof setTimeout> | null = null
/** 错误弹窗自动关闭延迟(毫秒) */
const ERROR_AUTO_CLOSE_DELAY = 5000

/* 取餐成功提示(非阻塞,2 秒后自动消失) */
const showSuccess = ref(false)
const successMsg = ref('')

/* 菜品查询弹窗(右下角浮动按钮触发) */
const showOrderQuery = ref(false)

let timer = 0
let successTimer: ReturnType<typeof setTimeout> | null = null
const updateClock = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLabel.value = fullDateLabel(now)
}

/**
 * 显示错误弹窗并启动 5 秒自动关闭定时器。
 * 每次调用前清除旧定时器,确保倒计时从本次显示开始。
 */
const showErrorWithAutoClose = (title: string, msg: string) => {
  errorTitle.value = title
  errorMsg.value = msg
  showError.value = true
  if (errorTimer) clearTimeout(errorTimer)
  errorTimer = setTimeout(() => {
    showError.value = false
    errorTimer = null
  }, ERROR_AUTO_CLOSE_DELAY)
}

/** 关闭错误弹窗并清除定时器 */
const dismissError = () => {
  showError.value = false
  if (errorTimer) {
    clearTimeout(errorTimer)
    errorTimer = null
  }
}

/**
 * 统一输入处理:USB 读卡器、扫码枪和摄像头扫码都作为键盘设备,Enter 结束输入。
 *
 * @param code 扫码/刷卡内容
 * @param fromCamera 是否来自摄像头扫码(默认 false,即来自读卡器/扫码枪)
 *
 * 安全策略:
 * - 读卡器(DLL/USB HID):接受纯数字卡号识别(物理持有,安全)
 * - 扫码枪/摄像头:接受一次性支付码(32位hex)+ 身份二维码,不接受纯卡号(防远程冒充)
 *
 * 依次尝试:
 *   1. 旧版身份二维码(以 { 开头,兼容)→ /terminal/verify-qrcode
 *   2. 一次性支付码(32位hex)→ /terminal/verify-paycode(扫码枪/摄像头都接受)
 *   3. 刷卡识别(仅读卡器/扫码枪,摄像头拒绝)→ 本地缓存
 */
const handleInput = async (code: string, fromCamera = false) => {
  if (scanning.value || !code) return
  scanning.value = true
  try {
    const trimmed = code.trim()

    // 1. 旧版身份二维码:内容为 JSON 对象(以 { 开头,含 sign 签名)→ 兼容
    if (trimmed.startsWith('{')) {
      try {
        const qr = JSON.parse(trimmed)
        if (qr.sign && qr.cardNo && qr.storeId && qr.employeeId && qr.expire) {
          const resp = await api.post('/terminal/verify-qrcode', qr)
          if (resp.data.code === 200 && resp.data.data) {
            resetPickupFlow()
            pickupStore.employee = resp.data.data
            router.push('/pickup/verify')
            return
          }
        }
      } catch {
        /* 非合法二维码 JSON,继续按其他方式处理 */
      }
    }

    // 2. 一次性支付码:32 位 hex(小写)→ /terminal/verify-paycode
    //    扫码枪和摄像头都接受,核销即失效,防截图重放
    if (/^[0-9a-f]{32}$/.test(trimmed)) {
      try {
        const resp = await api.post('/terminal/verify-paycode', { code: trimmed })
        if (resp.data.code === 200 && resp.data.data) {
          resetPickupFlow()
          pickupStore.employee = resp.data.data
          router.push('/pickup/verify')
          return
        }
      } catch {
        /* 支付码无效或已使用,继续尝试卡号识别 */
      }
    }

    // 3. 作为卡号识别员工(仅读卡器/扫码枪,摄像头不接受纯卡号防远程冒充)
    if (!fromCamera) {
      try {
        const emp = await getEmployeeByCardNo(trimmed)
        if (emp) {
          resetPickupFlow()
          pickupStore.employee = emp
          router.push('/pickup/verify')
          return
        }
      } catch { /* 非员工卡 */ }
    }

    // 所有方式均未匹配
    if (fromCamera) {
      showErrorWithAutoClose('取餐失败', '请扫描H5「我的」页生成的支付码')
    } else {
      showErrorWithAutoClose('取餐失败', '卡号不存在')
    }
  } catch (e: any) {
    showErrorWithAutoClose('取餐失败', '卡号不存在')
  } finally {
    scanning.value = false
  }
}

// 使用统一读卡器 composable:支持 Python Shell 读卡器 + USB HID 键盘(降级)
// 弹窗显示时仍接受刷卡:新刷卡会关闭弹窗并处理新卡号,不影响下一位员工
useCardReader((cardNo) => {
  // 正在处理中时不接受新输入(避免并发)
  if (scanning.value) return
  // 弹窗显示时:先关闭弹窗,再处理新卡号(不影响下一位员工刷卡)
  if (showError.value) {
    dismissError()
  }
  if (showOrderQuery.value) {
    showOrderQuery.value = false
  }
  handleInput(cardNo)
})

// ===== 摄像头后台扫码(无感,与读卡器并行) =====
// 自动启动摄像头,持续扫码,与读卡器同时工作,接受同一个防抖间隔
const cameraSupported = isCameraSupported()
const videoRef = ref<HTMLVideoElement | null>(null)
const cameraActive = ref(false) // 摄像头是否已启动

const {
  start: startCamera,
  stop: stopCamera,
  cameraAvailable,
} = useCameraScanner(
  (code) => {
    // 摄像头扫描到码:关闭弹窗并走统一输入处理
    // fromCamera=true:摄像头不接受纯卡号识别员工(防远程冒充)
    if (showError.value) dismissError()
    if (showOrderQuery.value) showOrderQuery.value = false
    handleInput(code, true)
  },
  // 使用读卡器的防抖间隔(秒 → 毫秒),保持一致
  { debounceMs: cardInterval.value * 1000 },
)

// ===== 设备在线检测(读卡器 + 摄像头) =====
// 读卡器:Python Shell 环境 3 秒轮询真实硬件状态;摄像头:由 cameraAvailable 驱动
const { hasCardReader, hasCamera } = useDevicePresence(cameraAvailable)

/** 待机页提示文字(根据在线设备动态变化) */
const scanHint = computed(() =>
  getScanHint(hasCardReader.value, hasCamera.value, true),
)

/** 待机页图标:确定只有读卡器(无摄像头)用 CreditCard,其余用 CreditCard(默认)
 *  不再使用 ScanLine:USB HID 读卡器无法检测,默认显示 CreditCard 更通用 */
const showScanIcon = computed(() => false)

/** 主刷卡图标点击:根据设备显示对应提示
 *  USB HID 读卡器无法检测,提示需兼顾刷卡和扫码 */
const onCardClick = () => {
  if (scanning.value) return
  if (hasCardReader.value && !hasCamera.value) {
    // 确定只有 DLL 读卡器(无摄像头):只提示刷卡
    showErrorWithAutoClose('提示', '请将员工卡放置在读卡器上')
  } else if (hasCamera.value) {
    // 有摄像头(可能还有 USB HID 读卡器):都提示
    showErrorWithAutoClose('提示', '请将员工卡放置在读卡器上,或扫描支付码/身份二维码')
  } else {
    // 无摄像头无读卡器:USB HID 读卡器/扫码枪可能存在,都提示
    showErrorWithAutoClose('提示', '请将员工卡放置在读卡器上,或使用扫码枪扫描支付码')
  }
}

onMounted(() => {
  resetPickupFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  // 前台拉取品牌信息(首次加载也立即展示缓存,再异步校验)
  fetchBranding()
  // 自动启动摄像头后台扫码(无感,与读卡器并行)
  if (cameraSupported) {
    // 等待 DOM 渲染 <video> 后再启动
    setTimeout(async () => {
      cameraActive.value = await startCamera(videoRef.value)
    }, 200)
  }
})
onUnmounted(() => {
  clearInterval(timer)
  if (successTimer) clearTimeout(successTimer)
  if (errorTimer) clearTimeout(errorTimer)
  stopCamera()
})
</script>

<template>
  <main class="pickup-standby" :class="{ 'pickup-standby--branded': !!branding?.terminalBackgroundUrl }">
    <!-- 顶栏:Logo + 食堂名称(左上) -->
    <header class="pickup-standby__header">
      <div class="pickup-standby__brand">
        <img
          v-if="branding?.logoUrl"
          :src="branding.logoUrl"
          :alt="storeName"
          class="pickup-standby__logo"
          @error="(e) => (e.target as HTMLImageElement).style.display = 'none'"
        />
        <span class="pickup-standby__name">{{ storeName }}</span>
      </div>
    </header>

    <!-- 中央:大时钟 + 刷卡区 -->
    <div class="pickup-standby__main">
      <!-- 大时钟(居中) -->
      <div class="pickup-standby__clock-section">
        <div class="pickup-standby__clock">{{ clock }}</div>
        <div class="pickup-standby__date">{{ dateLabel }}</div>
      </div>

      <!-- 刷卡区(呼吸动画) -->
      <div class="pickup-standby__scan-section">
        <button
          class="pickup-standby__scan-btn"
          :class="{ 'pickup-standby__scan-btn--loading': scanning }"
          @click="onCardClick"
          :disabled="scanning"
        >
          <Loader2 v-if="scanning" class="spinner" :size="56" />
          <div v-else class="pickup-standby__scan-icon">
            <ScanLine v-if="showScanIcon" :size="56" />
            <CreditCard v-else :size="56" />
          </div>
        </button>
        <div class="pickup-standby__scan-hint">
          {{ scanning ? '识别中...' : scanHint }}
        </div>

        <!-- 摄像头扫码按钮 -->
      </div>
    </div>

    <!-- 摄像头后台扫码(隐藏 video,仅用于 ZXing 解码) -->
    <video
      v-if="cameraSupported"
      ref="videoRef"
      autoplay
      playsinline
      muted
      class="pickup-standby__camera-hidden"
    />

    <!-- 摄像头状态指示器(右上角小图标) -->
    <div v-if="cameraSupported" class="pickup-standby__camera-status">
      <Camera :size="16" />
      <span class="pickup-standby__camera-status-dot" :class="cameraActive ? 'pickup-standby__camera-status-dot--on' : ''" />
    </div>

    <!-- 取餐成功提示(非阻塞,2 秒后消失) -->
    <Transition name="fade">
      <div v-if="showSuccess" class="pickup-standby__success-toast">
        {{ successMsg }}
      </div>
    </Transition>

    <!-- 右下角浮动按钮:订单查询 -->
    <button
      class="pickup-standby__query-btn btn-press"
      aria-label="订单查询"
      @click="showOrderQuery = true"
    >
      <Search :size="26" />
      <span class="pickup-standby__query-text">订单查询</span>
    </button>

    <!-- 订单查询弹窗 -->
    <OrderQueryModal v-model="showOrderQuery" />

    <!-- 错误提示弹窗(5 秒自动消失,下一位刷卡也会关闭) -->
    <Modal
      v-model="showError"
      :title="errorTitle"
      :message="errorMsg"
      variant="warning"
      :cancel-text="''"
      :close-on-overlay="false"
      confirm-text="知道了"
      @confirm="dismissError"
      @cancel="dismissError"
    />
  </main>
</template>

<style scoped>
.pickup-standby {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  /* 背景由 App.vue 全局提供(深色底色 + 品牌图),此处透明避免遮挡 */
  background: transparent;
  --ps-text: #ffffff;
  --ps-text-muted: rgba(255, 255, 255, 0.7);
}
/* 有品牌图时:保持白色文字(与默认一致,仅添加文字阴影增强可读性) */
.pickup-standby--branded {
  --ps-text: #ffffff;
  --ps-text-muted: rgba(255, 255, 255, 0.7);
}

/* 顶栏 */
.pickup-standby__header {
  position: relative;
  z-index: 1;
  padding: 20px 32px;
}
.pickup-standby__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pickup-standby__logo {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}
.pickup-standby__name {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--ps-text);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

/* 中央主区 */
.pickup-standby__main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 64px;
  padding: 24px;
}

/* 大时钟区(居中) */
.pickup-standby__clock-section {
  text-align: center;
  color: var(--ps-text);
}
.pickup-standby__clock {
  font-size: var(--fs-clock);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -2px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.pickup-standby__date {
  margin-top: 16px;
  font-size: var(--fs-xl);
  color: var(--ps-text-muted);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

/* 刷卡区(白色玻璃态呼吸动画) */
.pickup-standby__scan-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.pickup-standby__scan-btn {
  width: 140px;
  height: 140px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.1);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  /* X86 低端集显:animation 与 transition 并存会反复重建合成层导致闪烁,
     删除 transition 仅保留 pulse 动画(transform/opacity,见 style.css keyframes) */
  animation: pulse-ring 2.4s ease-in-out infinite;
}
.pickup-standby__scan-btn:active { transform: scale(0.95); }
.pickup-standby__scan-btn--loading {
  background: rgba(255, 255, 255, 0.1);
  animation: none;
}
.pickup-standby__scan-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  /* X86 低端集显禁用 filter:drop-shadow:GPU 逐帧重绘导致闪烁 */
}
.pickup-standby__scan-hint {
  font-size: var(--fs-lg);
  font-weight: 400;
  color: var(--ps-text);
}

/* 取餐成功 toast(顶部居中悬浮,非阻塞) */
.pickup-standby__success-toast {
  position: fixed;
  top: 32px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 100;
  padding: 16px 32px;
  border-radius: var(--doubao-radius);
  background: rgba(7, 193, 96, 0.95);
  color: #ffffff;
  font-size: var(--fs-lg);
  font-weight: 700;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
}

/* 摄像头后台扫码:隐藏 video 元素(ZXing 解码用,用户不可见) */
.pickup-standby__camera-hidden {
  position: absolute;
  width: 2px;
  height: 2px;
  opacity: 0;
  pointer-events: none;
  top: -9999px;
  left: -9999px;
}

/* 摄像头状态指示器(右上角小图标) */
.pickup-standby__camera-status {
  position: fixed;
  top: 20px;
  right: 24px;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.6);
  font-size: var(--fs-xs);
}
.pickup-standby__camera-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.8);
}
.pickup-standby__camera-status-dot--on {
  background: rgba(7, 193, 96, 0.9);
  box-shadow: 0 0 6px rgba(7, 193, 96, 0.6);
}

/* 竖屏适配 */
@media (orientation: portrait) {
  .pickup-standby__main { gap: 32px; }
}

/* 右下角浮动按钮:菜品查询(玻璃态,与待机页风格统一) */
.pickup-standby__query-btn {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 50;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 22px 0 18px;
  border: 1.5px solid rgba(255, 255, 255, 0.25);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
  font-family: inherit;
  font-size: var(--fs-base);
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
  transition: transform 0.15s ease, background 0.2s ease;
}
/* 触屏优化:hover 仅鼠标设备生效,触屏不粘滞 */
@media (hover: hover) and (pointer: fine) {
  .pickup-standby__query-btn:hover {
    background: rgba(255, 255, 255, 0.24);
  }
}
.pickup-standby__query-text {
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}

/* 低分辨率适配 */
@media (max-width: 1366px) {
  .pickup-standby__scan-btn { width: 120px; height: 120px; }
  .pickup-standby__scan-icon { width: 84px; height: 84px; }
}
</style>
