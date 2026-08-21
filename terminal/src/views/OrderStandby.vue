<script setup lang="ts">
/**
 * 订餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - 顶栏:Logo + 食堂名称
 * - 中央:大时钟 + 日期
 * - 底部:刷卡区
 *
 * 刷卡成功 → 跳转 /order/menu
 *
 * 设备策略:读卡器与扫码枪均为 USB HID 键盘模拟设备(无摄像头适配),
 * 提示文字固定为"请刷卡",不做设备在线检测轮询(低性能设备减负)。
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { getEmployeeByCardNo } from '@/utils/employeeCache'
import { orderStore, resetOrderFlow } from '@/store/order'
import { brandingState, fetchBranding } from '@/store/branding'
import { toDateKey, fullDateLabel, pad2 } from '@/utils'
import { CreditCard, Loader2 } from 'lucide-vue-next'

import { useCardReader } from '@/composables/useCardReader'

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

/**
 * 统一输入处理。
 * @param input 扫码/刷卡内容
 *
 * 安全策略:
 * - 读卡器(DLL/USB HID):接受纯数字卡号识别(物理持有,安全)
 * - 扫码枪(HID):接受一次性支付码(32位hex,核销即失效)+ 旧版JSON身份二维码(兼容)
 *   不接受纯卡号(防远程冒充:攻击者知道卡号即可生成二维码)
 *
 * 识别顺序:
 *   1. JSON 身份二维码(以 { 开头,兼容旧版)→ /terminal/verify-qrcode
 *   2. 32 位 hex 支付码 → /terminal/verify-paycode(一次性,防重放)
 *   3. 纯数字卡号 → 本地缓存识别
 */
const scan = async (input: string) => {
  if (scanning.value || !input.trim()) return
  scanning.value = true
  scanError.value = ''
  try {
    const trimmed = input.trim()

    // 1. 旧版身份二维码:内容为 JSON 对象(以 { 开头,含 sign 签名)→ 兼容
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
        /* 非合法二维码 JSON,继续按其他方式处理 */
      }
    }

    // 2. 一次性支付码:32 位 hex(小写)→ /terminal/verify-paycode
    //    扫码枪接受,核销即失效,防截图重放
    //    大小写归一化:部分 HID 扫码枪出厂输出大写,后端按小写 key 核销
    const payCode = trimmed.toLowerCase()
    if (/^[0-9a-f]{32}$/.test(payCode)) {
      try {
        const resp = await api.post('/terminal/verify-paycode', { code: payCode })
        if (resp.data.code === 200 && resp.data.data) {
          resetOrderFlow()
          orderStore.employee = resp.data.data
          orderStore.selectedDate = toDateKey(new Date())
          router.push('/order/menu')
          return
        }
      } catch {
        /* 支付码无效或已使用,继续尝试其他方式 */
      }
    }

    // 3. 读卡器/扫码枪:作为卡号识别员工(优先查本地缓存,毫秒级)
    const emp = await getEmployeeByCardNo(trimmed)
    if (emp) {
      resetOrderFlow()
      orderStore.employee = emp
      orderStore.selectedDate = toDateKey(new Date())
      router.push('/order/menu')
      return
    }
    scanError.value = '刷卡失败,请重试'
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

onMounted(() => {
  resetOrderFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  fetchBranding({ background: true })
})
onUnmounted(() => {
  clearInterval(timer)
  if (scanErrorTimer) clearTimeout(scanErrorTimer)
})
</script>

<template>
  <main class="standby" :class="{ 'standby--branded': !!branding?.terminalBackgroundUrl }">
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
          <div v-else class="standby__scan-icon">
            <CreditCard :size="56" />
          </div>
        </button>
        <div class="standby__scan-hint">
          {{ scanning ? '识别中...' : '请刷卡' }}
        </div>

        <!-- 错误提示 -->
        <div v-if="scanError" class="standby__error">{{ scanError }}</div>
      </div>
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
  /* 背景由 App.vue 全局提供(深色底色 + 品牌图),此处透明避免遮挡 */
  background: transparent;
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
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  /* X86 低端设备:取消脉冲等持续动画,GPU 持续重绘会导致闪烁/高负载 */
}
.standby__scan-btn:active { transform: scale(0.95); }
.standby__scan-btn--loading {
  background: rgba(255, 255, 255, 0.1);
}
.standby__scan-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  /* X86 低端集显禁用 filter:drop-shadow:GPU 逐帧重绘导致闪烁 */
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
