<script setup lang="ts">
/**
 * 订餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - 顶栏:Logo + 食堂名称
 * - 中央:大时钟 + 日期
 * - 底部:刷卡区(脉冲动画)+ 测试卡号(开发环境)
 *
 * 刷卡成功 → 跳转 /order/menu
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig } from '@/api'
import { orderStore, resetOrderFlow } from '@/store/order'
import { brandingState, fetchBranding } from '@/store/branding'
import { toDateKey, fullDateLabel, pad2 } from '@/utils'
import { CreditCard, Loader2 } from 'lucide-vue-next'
import BrandingBg from '@/components/BrandingBg.vue'

const router = useRouter()
const clock = ref('')
const dateLabel = ref('')
const scanning = ref(false)
const scanError = ref('')
const customCard = ref('')
// 测试模式:始终显示(满足硬约束"测试面板始终可见")
// loadTestEmployees 改用安全的 /terminal/employees(仅本店员工,无 PII)
const showTest = ref(true)

// 当前绑定信息(用于排查 token storeId 与门店不匹配问题)
const boundCfg = computed(() => {
  const c = loadConfig()
  if (!c) return null
  return {
    storeId: c.storeId,
    storeName: c.storeName,
    deviceLabel: c.deviceLabel,
    boundAt: c.boundAt ? new Date(c.boundAt).toLocaleString('zh-CN') : '',
    tokenHead: c.token ? c.token.slice(0, 20) + '...' : '',
  }
})

const gotoSettings = () => {
  router.push('/settings')
}

const branding = computed(() => brandingState.data)
const storeName = computed(() => branding.value?.name || '企业智慧食堂')

// 从后端拉取的真实员工列表(测试模拟刷卡用)
interface TestEmployee { id: number; cardNo: string; name: string; storeName?: string }
const testEmployees = ref<TestEmployee[]>([])
const testLoading = ref(false)
// 拉取失败时的错误信息(便于排查 token 失效/网络错误等)
const testError = ref('')

let timer = 0
let scanErrorTimer: ReturnType<typeof setTimeout> | null = null
const updateClock = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLabel.value = fullDateLabel(now)
}

const scan = async (cardNo: string) => {
  if (scanning.value || !cardNo.trim()) return
  scanning.value = true
  scanError.value = ''
  try {
    const resp = await api.get(`/terminal/employee/${encodeURIComponent(cardNo.trim())}`)
    if (resp.data.code === 200 && resp.data.data) {
      resetOrderFlow()
      orderStore.employee = resp.data.data
      orderStore.selectedDate = toDateKey(new Date())
      router.push('/order/menu')
      return
    }
    scanError.value = resp.data.message || '刷卡失败,请重试'
  } catch (e: any) {
    scanError.value = e?.response?.data?.message || '刷卡失败,请重试'
  } finally {
    scanning.value = false
    // 3 秒后清除错误提示
    if (scanErrorTimer) clearTimeout(scanErrorTimer)
    scanErrorTimer = setTimeout(() => { scanError.value = '' }, 3000)
  }
}

/** 拉取本店员工列表(需终端 token,仅返回本店员工的 id/cardNo/name) */
const loadTestEmployees = async () => {
  testLoading.value = true
  testError.value = ''
  try {
    const resp = await api.get('/terminal/employees')
    const list: any[] = resp.data?.code === 200 ? (resp.data.data || []) : []
    testEmployees.value = list.map((e: any) => ({
      id: Number(e.id),
      cardNo: String(e.cardNo || ''),
      name: String(e.name || ''),
      storeName: e.storeName ? String(e.storeName) : undefined,
    }))
    if (list.length === 0) {
      testError.value = '后端返回空数组(可能是 storeId 不匹配)'
    }
  } catch (e: any) {
    /* 拉取失败时显示具体错误,便于排查(401 已被全局拦截器处理并跳转配置页) */
    const status = e?.response?.status
    const msg = e?.response?.data?.message || e?.message || '未知错误'
    testError.value = `请求失败(${status || '无状态码'}): ${msg}`
    testEmployees.value = []
  } finally {
    testLoading.value = false
  }
}

/** 主刷卡图标点击:仅显示提示,不再随机选员工(避免泄露任意员工订单) */
const onCardClick = () => {
  if (scanning.value) return
  scanError.value = '请将员工卡放置在读卡器上'
  if (scanErrorTimer) clearTimeout(scanErrorTimer)
  scanErrorTimer = setTimeout(() => { scanError.value = '' }, 3000)
}

/* ============ 读卡器键盘输入:USB 读卡器作为键盘设备,缓冲字符识别卡号 ============ */
let cardBuffer = ''
let cardBufferTimer: ReturnType<typeof setTimeout> | null = null
const CARD_INPUT_TIMEOUT = 80

const onKeyPress = (e: KeyboardEvent) => {
  if (scanning.value || scanError.value) {
    cardBuffer = ''
    return
  }
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
      scan(cardNo)
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

/** 姓名脱敏:保留姓氏,其余用 * 替换 */
const maskName = (name: string): string => {
  if (!name) return ''
  if (name.length <= 1) return name
  return name[0] + '*'.repeat(name.length - 1)
}

/** 卡号脱敏:仅显示后 4 位 */
const maskCardNo = (cardNo: string): string => {
  if (!cardNo) return ''
  if (cardNo.length <= 4) return cardNo
  return '*'.repeat(cardNo.length - 4) + cardNo.slice(-4)
}

const onCustom = () => {
  const c = customCard.value.trim()
  if (!c) return
  scan(c)
  customCard.value = ''
}

onMounted(() => {
  resetOrderFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  fetchBranding({ background: true })
  loadTestEmployees()
  // 注册读卡器键盘监听:USB 读卡器作为键盘设备,在待机页直接刷卡进入订餐流程
  window.addEventListener('keydown', onKeyPress)
})
onUnmounted(() => {
  clearInterval(timer)
  if (scanErrorTimer) clearTimeout(scanErrorTimer)
  if (cardBufferTimer) clearTimeout(cardBufferTimer)
  window.removeEventListener('keydown', onKeyPress)
})
</script>

<template>
  <main class="standby" :class="{ 'standby--branded': !!branding?.terminalBackgroundUrl }">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.5" />

    <!-- 顶栏:Logo + 食堂名称 -->
    <header class="standby__header">
      <div class="standby__brand">
        <img
          v-if="branding?.logoUrl"
          :src="branding.logoUrl"
          :alt="storeName"
          class="standby__logo"
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
            <CreditCard :size="56" />
          </div>
        </button>
        <div class="standby__scan-hint">
          {{ scanning ? '识别中...' : '请将员工卡放置在感应区' }}
        </div>

        <!-- 错误提示 -->
        <div v-if="scanError" class="standby__error">{{ scanError }}</div>

        <!-- 自定义卡号输入(开发/调试用) -->
        <div class="standby__custom">
          <input
            v-model="customCard"
            type="text"
            placeholder="或输入卡号"
            class="standby__custom-input"
            @keyup.enter="onCustom"
          />
          <button class="standby__custom-btn" @click="onCustom" :disabled="!customCard.trim()">确认</button>
        </div>
      </div>
    </div>

    <!-- 底部:测试卡号(始终显示,便于模拟刷卡) -->
    <footer class="standby__footer">
      <div class="standby__test-title">
        测试模式 · 点击员工模拟刷卡{{ testLoading ? '(加载中...)' : `(${testEmployees.length}人)` }}
        <button class="standby__test-toggle" @click="loadTestEmployees" :disabled="testLoading">
          刷新
        </button>
        <button class="standby__test-toggle" @click="showTest = !showTest">
          {{ showTest ? '收起' : '展开' }}
        </button>
      </div>
      <template v-if="showTest">
        <!-- 当前绑定信息(排查 token storeId 不匹配) -->
        <div v-if="boundCfg" class="standby__test-bindinfo">
          <span>绑定门店: <b>{{ boundCfg.storeName }}</b>(ID={{ boundCfg.storeId }})</span>
          <span>设备: {{ boundCfg.deviceLabel || '-' }}</span>
          <span>绑定时间: {{ boundCfg.boundAt }}</span>
          <span class="standby__test-token">token: {{ boundCfg.tokenHead }}</span>
        </div>
        <div v-if="testEmployees.length === 0 && !testLoading" class="standby__test-empty">
          <div>暂无员工,请先在后台添加</div>
          <div v-if="testError" class="standby__test-err">{{ testError }}</div>
          <!-- 返回空数组往往是旧 token(storeId 与门店不匹配),提示重新绑定 -->
          <button class="standby__test-rebind" @click="gotoSettings">
            token 可能失效,去重新绑定 →
          </button>
        </div>
        <div v-else class="standby__test-cards">
          <button
            v-for="c in testEmployees"
            :key="c.id"
            class="standby__test-card"
            @click="scan(c.cardNo)"
            :disabled="scanning"
          >
            {{ maskCardNo(c.cardNo) }} · {{ maskName(c.name) }}
          </button>
        </div>
      </template>
    </footer>
  </main>
</template>

<style scoped>
.standby {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  width: 100%;
  overflow: hidden;
  /* 无品牌图时:浅灰背景 + 深色文字(避免白字不可读) */
  background: #eff1f4;
  --sb-text: #0e1115;
  --sb-text-muted: rgba(14, 17, 21, 0.6);
  --sb-glass-bg: rgba(14, 17, 21, 0.05);
  --sb-glass-border: rgba(14, 17, 21, 0.1);
  --sb-footer-bg: rgba(255, 255, 255, 0.7);
  --sb-footer-border: rgba(14, 17, 21, 0.08);
}
/* 有品牌图时:深色遮罩 + 白色文字(带阴影增强可读性) */
.standby--branded {
  --sb-text: #ffffff;
  --sb-text-muted: rgba(255, 255, 255, 0.7);
  --sb-glass-bg: rgba(255, 255, 255, 0.15);
  --sb-glass-border: rgba(255, 255, 255, 0.3);
  --sb-footer-bg: rgba(0, 0, 0, 0.4);
  --sb-footer-border: transparent;
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
}
.standby--branded .standby__clock {
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.standby__date {
  margin-top: 16px;
  font-size: var(--fs-xl);
  color: var(--sb-text-muted);
}
.standby--branded .standby__name,
.standby--branded .standby__date {
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
  border: none;
  background: var(--sb-glass-bg);
  backdrop-filter: blur(12px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sb-text);
  transition: transform 0.2s ease, background 0.2s ease;
}
.standby__scan-btn:active { transform: scale(0.95); }
.standby__scan-btn--loading { background: var(--sb-glass-bg); }
.standby__scan-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: var(--doubao-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}
.standby__scan-hint {
  font-size: var(--fs-lg);
  font-weight: 500;
  color: var(--sb-text);
}
.standby__error {
  padding: 8px 16px;
  border-radius: 999px;
  background: rgba(239, 68, 68, 0.9);
  color: white;
  font-size: var(--fs-sm);
}

/* 自定义卡号输入 */
.standby__custom {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
.standby__custom-input {
  width: 240px;
  height: 44px;
  padding: 0 16px;
  border: 1px solid var(--sb-glass-border);
  border-radius: 999px;
  background: var(--sb-glass-bg);
  color: var(--sb-text);
  font-size: var(--fs-sm);
  font-family: inherit;
  backdrop-filter: blur(8px);
}
.standby__custom-input::placeholder { color: var(--sb-text-muted); }
.standby__custom-input:focus { outline: 2px solid var(--doubao-primary); }
.standby__custom-btn {
  height: 44px;
  padding: 0 20px;
  border: none;
  border-radius: 999px;
  background: var(--doubao-primary);
  color: white;
  font-size: var(--fs-sm);
  font-weight: 600;
  cursor: pointer;
}
.standby__custom-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 底部测试卡号 */
.standby__footer {
  position: relative;
  z-index: 1;
  padding: 16px 32px 24px;
  background: var(--sb-footer-bg);
  border-top: 1px solid var(--sb-footer-border);
  backdrop-filter: blur(8px);
}
.standby__test-title {
  color: var(--sb-text-muted);
  font-size: var(--fs-xs);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.standby__test-toggle {
  border: none;
  background: transparent;
  color: var(--sb-text-muted);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.standby__test-empty {
  color: var(--sb-text-muted);
  font-size: var(--fs-xs);
  padding: 8px 0;
}
.standby__test-err {
  margin-top: 4px;
  color: #e11d48;
  font-size: var(--fs-xs);
  word-break: break-all;
  opacity: 0.9;
}
.standby__test-bindinfo {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding: 6px 0;
  font-size: var(--fs-xs);
  color: var(--sb-text-muted);
  border-bottom: 1px dashed var(--sb-glass-border);
  margin-bottom: 6px;
}
.standby__test-bindinfo b {
  color: var(--sb-text);
  font-weight: 600;
}
.standby__test-token {
  opacity: 0.7;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.standby__test-rebind {
  margin-top: 8px;
  padding: 4px 10px;
  border: 1px solid #e11d48;
  background: transparent;
  color: #e11d48;
  font-size: var(--fs-xs);
  border-radius: 4px;
  cursor: pointer;
}
.standby__test-rebind:hover {
  background: #e11d48;
  color: #fff;
}
.standby__test-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.standby__test-card {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--sb-glass-border);
  border-radius: 999px;
  background: var(--sb-glass-bg);
  color: var(--sb-text);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: background 0.15s ease;
}
.standby__test-card:hover { background: rgba(255, 255, 255, 0.15); }
.standby--branded .standby__test-card:hover { background: rgba(255, 255, 255, 0.2); }
.standby__test-card--muted { opacity: 0.6; }

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
