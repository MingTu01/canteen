<script setup lang="ts">
/**
 * 订餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - 顶栏:Logo + 食堂名称
 * - 中央:大时钟 + 日期
 * - 底部:刷卡区(脉冲动画)
 *
 * 刷卡成功 → 跳转 /order/menu
 *
 * 生产环境:仅支持 USB 读卡器(键盘模拟输入)与点击刷卡按钮提示
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { orderStore, resetOrderFlow } from '@/store/order'
import { brandingState, fetchBranding } from '@/store/branding'
import { toDateKey, fullDateLabel, pad2 } from '@/utils'
import { CreditCard, Loader2, Camera, ScanLine } from 'lucide-vue-next'
import BrandingBg from '@/components/BrandingBg.vue'
import { useCardReader } from '@/composables/useCardReader'
import { useCameraScanner, isCameraSupported } from '@/composables/useCameraScanner'
import { useDevicePresence, getScanHint } from '@/composables/useDevicePresence'
import { cardInterval } from '@/store/terminalSettings'

const router = useRouter()
const clock = ref('')
const dateLabel = ref('')
const scanning = ref(false)
const scanError = ref('')

const branding = computed(() => brandingState.data)
const storeName = computed(() => branding.value?.name || '企业智慧食堂')

let timer = 0
let scanErrorTimer: ReturnType<typeof setTimeout> | null = null
const updateClock = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLabel.value = fullDateLabel(now)
}

const scan = async (input: string) => {
  if (scanning.value || !input.trim()) return
  scanning.value = true
  scanError.value = ''
  try {
    const trimmed = input.trim()

    // 1. 员工身份二维码:内容为 JSON 对象(以 { 开头,含 sign 签名)
    if (trimmed.startsWith('{')) {
      try {
        const qr = JSON.parse(trimmed)
        if (qr.sign && qr.cardNo && qr.storeId && qr.employeeId && qr.expire) {
          const resp = await api.post('/terminal/verify-qrcode', qr)
          if (resp.data.code === 200 && resp.data.data) {
            resetOrderFlow()
            orderStore.employee = resp.data.data
            orderStore.selectedDate = toDateKey(new Date())
            router.push('/order/menu')
            return
          }
        }
      } catch {
        /* 非合法二维码 JSON,继续按卡号处理 */
      }
    }

    // 2. 作为卡号识别员工
    const resp = await api.get(`/terminal/employee/${encodeURIComponent(trimmed)}`)
    if (resp.data.code === 200 && resp.data.data) {
      resetOrderFlow()
      orderStore.employee = resp.data.data
      orderStore.selectedDate = toDateKey(new Date())
      router.push('/order/menu')
      return
    }
    scanError.value = resp.data.message ?? '刷卡失败,请重试'
  } catch (e: any) {
    scanError.value = e?.response?.data?.message ?? '刷卡失败,请重试'
  } finally {
    scanning.value = false
    // 3 秒后清除错误提示
    if (scanErrorTimer) clearTimeout(scanErrorTimer)
    scanErrorTimer = setTimeout(() => { scanError.value = '' }, 3000)
  }
}

/** 主刷卡图标点击:仅显示提示(生产环境需真实读卡器) */
const onCardClick = () => {
  if (scanning.value) return
  scanError.value = '请将员工卡放置在读卡器上'
  if (scanErrorTimer) clearTimeout(scanErrorTimer)
  scanErrorTimer = setTimeout(() => { scanError.value = '' }, 3000)
}

// 使用统一读卡器 composable:支持 Python Shell 读卡器 + USB HID 键盘(降级)
useCardReader((cardNo) => {
  if (scanning.value || scanError.value) return
  scan(cardNo)
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
    scan(code)
  },
  // 使用读卡器的防抖间隔(秒 → 毫秒),保持一致
  { debounceMs: cardInterval.value * 1000 },
)

// ===== 设备在线检测(读卡器 + 摄像头) =====
// 读卡器:Python Shell 环境 3 秒轮询真实硬件状态;摄像头:由 cameraAvailable 驱动
const { hasCardReader, hasCamera } = useDevicePresence(cameraAvailable)

/** 待机页提示文字(根据在线设备动态变化) */
const scanHint = computed(() =>
  getScanHint(hasCardReader.value, hasCamera.value, false),
)

/** 待机页图标:只有摄像头/扫码枪(无读卡器)用 ScanLine,其余用 CreditCard */
const showScanIcon = computed(() => !hasCardReader.value && hasCamera.value)

onMounted(() => {
  resetOrderFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  fetchBranding({ background: true })
  // 自动启动摄像头后台扫码(无感,与读卡器并行)
  if (cameraSupported) {
    setTimeout(async () => {
      cameraActive.value = await startCamera(videoRef.value)
    }, 200)
  }
})
onUnmounted(() => {
  clearInterval(timer)
  if (scanErrorTimer) clearTimeout(scanErrorTimer)
  stopCamera()
})
</script>

<template>
  <main class="standby" :class="{ 'standby--branded': !!branding?.terminalBackgroundUrl }">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.15" />

    <!-- 顶栏:Logo + 食堂名称 -->
    <header class="standby__header">
      <div class="standby__brand">
        <img
          v-if="branding?.logoUrl"
          :src="branding.logoUrl"
          :alt="storeName"
          class="standby__logo"
          @error="(e) => (e.target as HTMLImageElement).style.display = 'none'"
        />
        <span class="standby__name">{{ storeName }}</span>
      </div>
    </header>

    <!-- 中央:大时钟 + 刷卡区 -->
    <div class="standby__main">
      <!-- 大时钟(居中) -->
      <div class="standby__clock-section">
        <div class="standby__clock">{{ clock }}</div>
        <div class="standby__date">{{ dateLabel }}</div>
      </div>

      <!-- 刷卡区 -->
      <div class="standby__scan-section">
        <button
          class="standby__scan-btn"
          :class="{ 'standby__scan-btn--loading': scanning }"
          @click="onCardClick"
          :disabled="scanning"
        >
          <Loader2 v-if="scanning" class="spinner" :size="56" />
          <div v-else class="standby__scan-icon card-pulse">
            <ScanLine v-if="showScanIcon" :size="56" />
            <CreditCard v-else :size="56" />
          </div>
        </button>
        <div class="standby__scan-hint">
          {{ scanning ? '识别中...' : scanHint }}
        </div>

        <!-- 错误提示 -->
        <div v-if="scanError" class="standby__error">{{ scanError }}</div>

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
      class="standby__camera-hidden"
    />

    <!-- 摄像头状态指示器(右上角小图标) -->
    <div v-if="cameraSupported" class="standby__camera-status">
      <Camera :size="16" />
      <span class="standby__camera-status-dot" :class="cameraActive ? 'standby__camera-status-dot--on' : ''" />
    </div>
  </main>
</template>

<style scoped>
.standby {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  /* 订餐端统一使用深色背景 + 白色文字(与取餐端一致) */
  background: var(--doubao-foreground);
  --sb-text: #ffffff;
  --sb-text-muted: rgba(255, 255, 255, 0.7);
  --sb-glass-bg: rgba(255, 255, 255, 0.1);
  --sb-glass-border: rgba(255, 255, 255, 0.2);
}
/* 有品牌图时:保持白色文字(与默认一致,仅添加文字阴影增强可读性) */
.standby--branded {
  --sb-text: #ffffff;
  --sb-text-muted: rgba(255, 255, 255, 0.7);
  --sb-glass-bg: rgba(255, 255, 255, 0.1);
  --sb-glass-border: rgba(255, 255, 255, 0.2);
}

/* 顶栏 */
.standby__header {
  position: relative;
  z-index: 1;
  padding: 20px 32px;
}
.standby__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.standby__logo {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}
.standby__name {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--sb-text);
}

/* 中央主区 */
.standby__main {
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
.standby__clock-section {
  text-align: center;
  color: var(--sb-text);
}
.standby__clock {
  font-size: var(--fs-clock);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -2px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.standby__date {
  margin-top: 16px;
  font-size: var(--fs-xl);
  color: var(--sb-text-muted);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}
.standby__name {
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

/* 刷卡区 */
.standby__scan-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.standby__scan-btn {
  width: 140px;
  height: 140px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(8px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  animation: pulse-ring 2.4s ease-in-out infinite;
  transition: transform 0.2s ease, background 0.2s ease;
}
.standby__scan-btn:active { transform: scale(0.95); }
.standby__scan-btn--loading {
  background: rgba(255, 255, 255, 0.1);
  animation: none;
}
.standby__scan-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.3));
}
.standby__scan-hint {
  font-size: var(--fs-lg);
  font-weight: 400;
  color: var(--sb-text);
}
.standby__error {
  padding: 8px 16px;
  border-radius: 999px;
  background: rgba(239, 68, 68, 0.9);
  color: white;
  font-size: var(--fs-sm);
}

/* 摄像头后台扫码:隐藏 video 元素(ZXing 解码用,用户不可见) */
.standby__camera-hidden {
  position: absolute;
  width: 2px;
  height: 2px;
  opacity: 0;
  pointer-events: none;
  top: -9999px;
  left: -9999px;
}

/* 摄像头状态指示器(右上角小图标) */
.standby__camera-status {
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
  backdrop-filter: blur(8px);
  color: rgba(255, 255, 255, 0.6);
  font-size: var(--fs-xs);
}
.standby__camera-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.8);
}
.standby__camera-status-dot--on {
  background: rgba(7, 193, 96, 0.9);
  box-shadow: 0 0 6px rgba(7, 193, 96, 0.6);
}

/* 底部设备信息(已移除,管理入口改为右上角 6 次点击) */

/* 竖屏适配 */
@media (orientation: portrait) {
  .standby__main { gap: 32px; }
}

/* 低分辨率适配 */
@media (max-width: 1366px) {
  .standby__scan-btn { width: 120px; height: 120px; }
  .standby__scan-icon { width: 84px; height: 84px; }
}
</style>
