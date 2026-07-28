<script setup lang="ts">
/**
 * 取餐待机页
 *
 * 终端空闲时显示:
 * - 食堂品牌背景图(若有)
 * - Logo + 食堂名 + 大时钟
 * - 刷卡/扫码提示(USB 读卡器和扫码枪作为键盘设备,Enter 结束输入)
 *   - 先尝试刷卡(员工接口),失败再尝试取餐码核销
 * - 测试卡号面板(部署/演示环境始终显示)
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import api, { loadConfig } from '@/api'
import { pickupStore, resetPickupFlow } from '@/store/pickup'
import { brandingState, fetchBranding } from '@/store/branding'
import { fullDateLabel, pad2 } from '@/utils'
import { CreditCard } from 'lucide-vue-next'
import BrandingBg from '@/components/BrandingBg.vue'
import Modal from '@/components/Modal.vue'

const router = useRouter()
const clock = ref('')
const dateLabel = ref('')
const scanning = ref(false)

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

// 测试模式:始终显示(满足硬约束"测试面板始终可见")
// 生产环境 TestController 被 @Profile("dev") 排除,ensureTestOrder 会 404 静默
// loadTestEmployees 改用安全的 /terminal/employees(仅本店员工,无 PII)
const showTest = ref(true)
const customCard = ref('')
// 从后端拉取的真实员工列表(测试模拟刷卡用)
interface TestEmployee { id: number; cardNo: string; name: string; storeName?: string }
const testEmployees = ref<TestEmployee[]>([])
const testLoading = ref(false)
// 拉取失败时的错误信息(便于排查 token 失效/网络错误等)
const testError = ref('')

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
    errorMsg.value = resp.data.message || '卡号/取餐码无效,请重试'
    showError.value = true
  } catch (e: any) {
    errorTitle.value = '取餐失败'
    errorMsg.value = e?.response?.data?.message || '卡号/取餐码无效,请重试'
    showError.value = true
  } finally {
    scanning.value = false
  }
}

/** 刷卡取餐(保留旧函数名兼容测试面板) */
const scanByCard = handleInput

/* ============ 读卡器键盘输入:USB 读卡器作为键盘设备,缓冲字符识别卡号 ============ */
let cardBuffer = ''
let cardBufferTimer: ReturnType<typeof setTimeout> | null = null
const CARD_INPUT_TIMEOUT = 80 // 读卡器单字符间隔通常 < 50ms,80ms 兜底

const onKeyPress = (e: KeyboardEvent) => {
  // 弹窗显示时不接受刷卡,避免与表单输入冲突
  if (showError.value || scanning.value) {
    cardBuffer = ''
    return
  }
  // 排除输入框焦点态(自定义卡号输入框),避免读卡器与键盘输入互相干扰
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
  // Enter 键:卡号输入结束,触发刷卡
  if (e.key === 'Enter') {
    if (cardBuffer.length > 0) {
      const cardNo = cardBuffer
      cardBuffer = ''
      if (cardBufferTimer) {
        clearTimeout(cardBufferTimer)
        cardBufferTimer = null
      }
      handleInput(cardNo)
    }
    return
  }
  // 累积可打印字符
  if (e.key.length === 1) {
    cardBuffer += e.key
    if (cardBufferTimer) clearTimeout(cardBufferTimer)
    cardBufferTimer = setTimeout(() => {
      cardBuffer = ''
      cardBufferTimer = null
    }, CARD_INPUT_TIMEOUT)
  }
}

/**
 * 为测试员工创建今天的测试订单(绕过登录/截止/余额校验,仅用于取餐端测试)。
 * 仅在开发环境调用,生产环境 TestController 被 @Profile("dev") 排除,
 * 前端通过 import.meta.env.DEV 进一步门控,避免生产环境产生 404 噪音日志。
 */
const ensureTestOrder = async (employeeId: number, mealType: number = 2) => {
  if (!import.meta.env.DEV) return
  try {
    await api.post(`/test/create-order?employeeId=${employeeId}&mealType=${mealType}`)
  } catch {
    /* 已有订单或其他错误均忽略,后续刷卡流程会正常处理 */
  }
}

/** 点击测试员工卡片:先创建测试订单,再刷卡进入取餐 */
const onTestCardClick = async (emp: TestEmployee) => {
  if (scanning.value) return
  await ensureTestOrder(emp.id)
  scanByCard(emp.cardNo)
}

const onCustomCard = () => {
  const c = customCard.value.trim()
  if (!c) return
  customCard.value = '' // 清空输入框,便于连续刷卡
  handleInput(c)
}

/** 姓名脱敏:保留姓氏,其余用 * 替换(如"张三"→"张*","欧阳锋"→"欧**") */
const maskName = (name: string): string => {
  if (!name) return ''
  if (name.length <= 1) return name
  return name[0] + '*'.repeat(name.length - 1)
}

/** 卡号脱敏:仅显示后 4 位,前缀用 * 替换(如"1234567890"→"******7890") */
const maskCardNo = (cardNo: string): string => {
  if (!cardNo) return ''
  if (cardNo.length <= 4) return cardNo
  return '*'.repeat(cardNo.length - 4) + cardNo.slice(-4)
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

onMounted(() => {
  resetPickupFlow()
  updateClock()
  timer = window.setInterval(updateClock, 1000)
  // 前台拉取品牌信息(首次加载也立即展示缓存,再异步校验)
  fetchBranding()
  loadTestEmployees()
  // 注册读卡器键盘监听:USB 读卡器/扫码枪作为键盘设备,在待机页直接刷卡/扫码取餐
  window.addEventListener('keydown', onKeyPress)
})
onUnmounted(() => {
  clearInterval(timer)
  if (successTimer) clearTimeout(successTimer)
  if (cardBufferTimer) clearTimeout(cardBufferTimer)
  window.removeEventListener('keydown', onKeyPress)
})
</script>

<template>
  <main class="pickup-standby" :class="{ 'pickup-standby--branded': !!branding?.terminalBackgroundUrl }">
    <BrandingBg :bg-url="branding?.terminalBackgroundUrl" :overlay-opacity="0.45" />

    <!-- 顶栏:Logo + 食堂名 -->
    <div class="pickup-standby__brand">
      <img
        v-if="branding?.logoUrl"
        :src="branding.logoUrl"
        :alt="storeName"
        class="pickup-standby__logo"
      />
      <div v-else class="pickup-standby__logo-fallback">
        <CreditCard :size="28" />
      </div>
    </div>

    <!-- 大时钟(居中) -->
    <div class="pickup-standby__clock">{{ clock }}</div>
    <div class="pickup-standby__date">{{ dateLabel }}</div>

    <!-- 标题 -->
    <div class="pickup-standby__title">{{ storeName }} - 取餐窗口</div>

    <!-- 提示:直接刷卡或扫码即可,无需点击 -->
    <div class="pickup-standby__hint">
      <div class="pickup-standby__hint-icon">
        <CreditCard :size="64" />
      </div>
      <span>{{ scanning ? '识别中...' : '请刷卡或扫码取餐' }}</span>
    </div>

    <!-- 取餐成功提示(非阻塞,2 秒后消失) -->
    <Transition name="fade">
      <div v-if="showSuccess" class="pickup-standby__success-toast">
        {{ successMsg }}
      </div>
    </Transition>

    <!-- 测试模式面板(部署/演示环境始终显示,便于模拟刷卡) -->
    <div class="pickup-standby__test">
      <div class="pickup-standby__test-head">
        <span>测试模式 · 点击员工模拟刷卡{{ testLoading ? '(加载中...)' : `(${testEmployees.length}人)` }}</span>
        <button class="pickup-standby__test-toggle" @click="loadTestEmployees" :disabled="testLoading">
          刷新
        </button>
        <button class="pickup-standby__test-toggle" @click="showTest = !showTest">
          {{ showTest ? '收起' : '展开' }}
        </button>
      </div>
      <div v-if="showTest" class="pickup-standby__test-body">
        <!-- 当前绑定信息(排查 token storeId 不匹配) -->
        <div v-if="boundCfg" class="pickup-standby__test-bindinfo">
          <span>绑定门店: <b>{{ boundCfg.storeName }}</b>(ID={{ boundCfg.storeId }})</span>
          <span>设备: {{ boundCfg.deviceLabel || '-' }}</span>
          <span>绑定时间: {{ boundCfg.boundAt }}</span>
          <span class="pickup-standby__test-token">token: {{ boundCfg.tokenHead }}</span>
        </div>
        <div v-if="testEmployees.length === 0 && !testLoading" class="pickup-standby__test-empty">
          <div>暂无员工,请先在后台添加员工</div>
          <div v-if="testError" class="pickup-standby__test-err">{{ testError }}</div>
          <!-- 返回空数组往往是旧 token(storeId 与门店不匹配),提示重新绑定 -->
          <button class="pickup-standby__test-rebind" @click="gotoSettings">
            token 可能失效,去重新绑定 →
          </button>
        </div>
        <div v-else class="pickup-standby__test-grid">
          <button
            v-for="c in testEmployees"
            :key="c.id"
            class="pickup-standby__test-card"
            :disabled="scanning"
            @click="onTestCardClick(c)"
          >
            <div class="pickup-standby__test-name">{{ maskName(c.name) }}</div>
            <div class="pickup-standby__test-no">{{ maskCardNo(c.cardNo) }}</div>
          </button>
        </div>
        <div class="pickup-standby__test-input">
          <input
            v-model="customCard"
            type="text"
            placeholder="输入卡号或取餐码"
            @keyup.enter="onCustomCard"
          />
          <button
            :disabled="scanning || !customCard.trim()"
            @click="onCustomCard"
          >确认</button>
        </div>
      </div>
    </div>

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
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  gap: 24px;
  padding: 40px 24px 80px;
  /* 取餐端统一使用深色背景 + 白色文字 */
  background: var(--doubao-foreground);
  --ps-text: #ffffff;
  --ps-text-muted: rgba(255, 255, 255, 0.7);
  overflow: hidden;
}
/* 有品牌图时:保持白色文字(与默认一致,仅添加文字阴影增强可读性) */
.pickup-standby--branded {
  --ps-text: #ffffff;
  --ps-text-muted: rgba(255, 255, 255, 0.7);
}
.pickup-standby > *:not(.branding-bg) {
  position: relative;
  z-index: 1;
}

.pickup-standby__logo {
  width: 48px;
  height: 48px;
  border-radius: var(--doubao-radius-sm);
  object-fit: cover;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}
.pickup-standby__logo-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}

.pickup-standby__clock {
  font-size: var(--fs-clock);
  font-weight: 700;
  color: var(--ps-text);
  letter-spacing: -2px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.pickup-standby--branded .pickup-standby__clock {
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.pickup-standby__date {
  font-size: var(--fs-xl);
  color: var(--ps-text-muted);
  margin-top: -4px;
}
.pickup-standby--branded .pickup-standby__date {
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}
.pickup-standby__title {
  font-size: var(--fs-xl);
  font-weight: 600;
  color: var(--ps-text);
  text-align: center;
}
.pickup-standby--branded .pickup-standby__title {
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.pickup-standby__hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  font-size: var(--fs-xl);
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
}
.pickup-standby__hint-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 140px;
  height: 140px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  border: 2px solid rgba(255, 255, 255, 0.2);
  color: #ffffff;
  backdrop-filter: blur(8px);
  animation: pulse-ring 2.4s ease-in-out infinite;
}
.pickup-standby__hint-icon > * {
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.3));
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
  font-weight: 600;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(8px);
}

/* 测试面板 */
.pickup-standby__test {
  width: 100%;
  max-width: 420px;
  border-radius: var(--doubao-radius);
  border: 1px solid var(--doubao-border);
  background: var(--doubao-card);
  margin-top: 16px;
}
.pickup-standby__test-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid var(--doubao-border);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--doubao-secondary-foreground);
}
.pickup-standby__test-toggle {
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.pickup-standby__test-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pickup-standby__test-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.pickup-standby__test-empty {
  padding: 16px;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}
.pickup-standby__test-err {
  margin-top: 6px;
  color: #e11d48;
  font-size: var(--fs-xs);
  word-break: break-all;
  opacity: 0.9;
}
.pickup-standby__test-bindinfo {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding: 6px 0;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  border-bottom: 1px dashed var(--doubao-border);
  margin-bottom: 6px;
}
.pickup-standby__test-bindinfo b {
  color: var(--doubao-foreground);
  font-weight: 600;
}
.pickup-standby__test-token {
  opacity: 0.7;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.pickup-standby__test-rebind {
  margin-top: 8px;
  padding: 4px 10px;
  border: 1px solid #e11d48;
  background: transparent;
  color: #e11d48;
  font-size: var(--fs-xs);
  border-radius: 4px;
  cursor: pointer;
}
.pickup-standby__test-rebind:hover {
  background: #e11d48;
  color: #fff;
}
.pickup-standby__test-card {
  padding: 10px 12px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: none;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}
.pickup-standby__test-card:disabled { opacity: 0.5; cursor: not-allowed; }
.pickup-standby__test-name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--doubao-foreground);
}
.pickup-standby__test-no {
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}
.pickup-standby__test-input {
  display: flex;
  gap: 8px;
}
.pickup-standby__test-input input {
  flex: 1;
  padding: 10px 12px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: none;
  color: var(--doubao-foreground);
  font-size: var(--fs-sm);
  font-family: inherit;
}
.pickup-standby__test-input button {
  padding: 10px 16px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-primary);
  border: none;
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-sm);
  font-weight: 600;
  cursor: pointer;
}
.pickup-standby__test-input button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
