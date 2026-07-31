<script setup lang="ts">
/**
 * 取餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - Logo + 食堂名 + 大时钟
 * - 刷卡/扫码提示(USB 读卡器和扫码枪作为键盘设备,Enter 结束输入)
 *   - 先尝试刷卡(员工接口),失败再尝试取餐码核销
 *
 * 生产环境:仅支持 USB 读卡器/扫码枪(键盘模拟输入)
 * 测试模式已移除,如需调试请在开发环境(import.meta.env.DEV)单独添加
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { pickupStore, resetPickupFlow } from '@/store/pickup'
import { brandingState, fetchBranding } from '@/store/branding'
import { fullDateLabel, pad2 } from '@/utils'
import { CreditCard, Loader2 } from 'lucide-vue-next'
import BrandingBg from '@/components/BrandingBg.vue'
import Modal from '@/components/Modal.vue'
import { useCardReader } from '@/composables/useCardReader'

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

/* 取餐成功提示(非阻塞,2 秒后自动消失) */
const showSuccess = ref(false)
const successMsg = ref('')

let timer = 0
let successTimer: ReturnType<typeof setTimeout> | null = null
const updateClock = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLabel.value = fullDateLabel(now)
}

/**
 * 统一输入处理:USB 读卡器和扫码枪都作为键盘设备,Enter 结束输入。
 * 先尝试刷卡(员工接口),失败再尝试取餐码核销。
 * 这样无需用户区分两种设备,直接刷卡/扫码即可。
 */
const handleInput = async (code: string) => {
  if (scanning.value || !code) return
  scanning.value = true
  try {
    // 1. 先尝试作为卡号识别员工
    try {
      const empResp = await api.get(`/terminal/employee/${encodeURIComponent(code)}`)
      if (empResp.data.code === 200 && empResp.data.data) {
        resetPickupFlow()
        pickupStore.employee = empResp.data.data
        router.push('/pickup/verify')
        return
      }
    } catch { /* 非员工卡,继续尝试取餐码 */ }

    // 2. 作为取餐码核销
    const resp = await api.post('/order/pickup', { pickupCode: code })
    if (resp.data.code === 200) {
      successMsg.value = '取餐成功,请前往取餐口领取餐品'
      showSuccess.value = true
      if (successTimer) clearTimeout(successTimer)
      successTimer = setTimeout(() => {
        showSuccess.value = false
      }, 2000)
      return
    }
    errorTitle.value = '取餐失败'
    errorMsg.value = resp.data.message ?? '卡号/取餐码无效,请重试'
    showError.value = true
  } catch (e: any) {
    errorTitle.value = '取餐失败'
    errorMsg.value = e?.response?.data?.message ?? '卡号/取餐码无效,请重试'
    showError.value = true
  } finally {
    scanning.value = false
  }
}

// 使用统一读卡器 composable:支持 Python Shell 读卡器 + USB HID 键盘(降级)
// 弹窗显示时不接受刷卡
useCardReader((cardNo) => {
  if (showError.value || scanning.value) return
  handleInput(cardNo)
})

/** 主刷卡图标点击:仅显示提示(生产环境需真实读卡器) */
const onCardClick = () => {
  if (scanning.value) return
  errorTitle.value = '提示'
  errorMsg.value = '请将员工卡放置在读卡器上,或使用扫码枪扫描取餐码'
  showError.value = true
}

onMounted(() => {
  resetPickupFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  // 前台拉取品牌信息(首次加载也立即展示缓存,再异步校验)
  fetchBranding()
})
onUnmounted(() => {
  clearInterval(timer)
  if (successTimer) clearTimeout(successTimer)
})
</script>

<template>
  <main class="pickup-standby" :class="{ 'pickup-standby--branded': !!branding?.terminalBackgroundUrl }">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.15" />

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
            <CreditCard :size="56" />
          </div>
        </button>
        <div class="pickup-standby__scan-hint">
          {{ scanning ? '识别中...' : '请刷卡或扫码取餐' }}
        </div>
      </div>
    </div>

    <!-- 取餐成功提示(非阻塞,2 秒后消失) -->
    <Transition name="fade">
      <div v-if="showSuccess" class="pickup-standby__success-toast">
        {{ successMsg }}
      </div>
    </Transition>

    <!-- 错误提示弹窗(替换原生 alert) -->
    <Modal
      v-model="showError"
      :title="errorTitle"
      :message="errorMsg"
      variant="warning"
      :cancel-text="''"
      confirm-text="知道了"
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
  /* 取餐端统一使用深色背景 + 白色文字(与订餐端一致) */
  background: var(--doubao-foreground);
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
  backdrop-filter: blur(8px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  animation: pulse-ring 2.4s ease-in-out infinite;
  transition: transform 0.2s ease, background 0.2s ease;
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
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.3));
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
  backdrop-filter: blur(8px);
}

/* 竖屏适配 */
@media (orientation: portrait) {
  .pickup-standby__main { gap: 32px; }
}

/* 低分辨率适配 */
@media (max-width: 1366px) {
  .pickup-standby__scan-btn { width: 120px; height: 120px; }
  .pickup-standby__scan-icon { width: 84px; height: 84px; }
}
</style>
