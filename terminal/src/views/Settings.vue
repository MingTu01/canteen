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
import {
  Server,
  Save,
  RotateCcw,
  Info,
  ShieldCheck,
  Loader2,
  CheckCircle2,
  ShoppingCart,
  ClipboardList,
} from 'lucide-vue-next'
import { loadConfig, bindTerminal, clearConfig, saveConfig, type TerminalConfig } from '@/api'
import { clearBranding } from '@/store/branding'
import { destroyLocalCache } from '@/utils/cache'
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

// ===== 解绑(需管理员密码二次校验,防止未授权人员物理接触后解绑) =====
const unbindConfirmVisible = ref(false)
const unbindVerifying = ref(false)
const unbindVerifyError = ref('')
// 二次校验:管理员用户名 + 密码(调用 /admin/login 验证,不依赖本地保存的凭据)
const unbindForm = ref({ username: '', password: '' })

const reloadBound = () => {
  boundConfig.value = loadConfig()
  if (boundConfig.value) {
    // 已绑定时,同步表单默认值(方便重新绑定)
    form.value.serverUrl = boundConfig.value.serverUrl
    form.value.deviceLabel = boundConfig.value.deviceLabel
    form.value.mode = boundConfig.value.mode
  }
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
  try {
    await bindTerminal({
      serverUrl: form.value.serverUrl.trim(),
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

/** 进入运行模式(根据当前 mode 跳转) */
const goRun = () => {
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

onMounted(reloadBound)
onBeforeUnmount(() => {
  if (bindSuccessTimer) clearTimeout(bindSuccessTimer)
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

          <!-- 主操作按钮 -->
          <button
            class="settings__primary-btn btn-press"
            @click="goRun"
          >
            <CheckCircle2 :size="22" />
            <span>进入运行模式</span>
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
              填入服务器域名、管理员账号密码、食堂安全码完成绑定。绑定后终端将被锁定到该食堂,无法越权访问其他门店。
            </p>
            <div class="settings__form">
              <div class="settings__field">
                <label class="settings__label">服务器域名(留空=同源开发模式)</label>
                <input
                  v-model="form.serverUrl"
                  type="text"
                  class="settings__input"
                  placeholder="留空走当前站点代理,或填 https://canteen.xxx.com"
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
          <!-- 成功提示 -->
          <div v-if="bindSuccess" class="settings__alert settings__alert--success">
            <CheckCircle2 :size="18" class="settings__alert-icon" />
            <span>绑定成功,即将进入运行模式...</span>
          </div>

          <!-- 绑定按钮 -->
          <button
            class="settings__primary-btn btn-press"
            :disabled="binding"
            @click="doBind"
          >
            <Loader2 v-if="binding" class="spinner" :size="22" />
            <Save v-else :size="22" />
            <span>{{ binding ? '绑定中...' : '测试并绑定' }}</span>
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
              <dd>v0.0.1</dd>
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
          解绑后本机将清除食堂绑定信息,需重新输入管理员账号和安全码才能使用。为防止误操作,请输入管理员账号密码确认。
        </p>
        <div class="modal__form">
          <input
            v-model="unbindForm.username"
            type="text"
            placeholder="管理员账号"
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
  min-height: 100vh;
  background: var(--doubao-background);
}
.settings__body {
  flex: 1;
  overflow-y: auto;
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
  font-weight: 600;
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
  font-weight: 600;
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
}
.settings__field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.settings__label {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
}
.settings__input {
  width: 100%;
  padding: 14px 16px;
  border-radius: var(--doubao-radius-sm);
  background: var(--doubao-muted);
  border: 1.5px solid transparent;
  color: var(--doubao-foreground);
  font-size: var(--fs-base);
  font-family: inherit;
  outline: none;
  transition: border-color 0.16s ease, background 0.16s ease;
}
.settings__input:focus {
  border-color: var(--doubao-primary);
  background: var(--doubao-card);
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
  font-weight: 600;
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
  font-weight: 500;
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
.settings__alert-icon { flex-shrink: 0; }

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
  font-weight: 600;
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
