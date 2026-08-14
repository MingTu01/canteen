<script setup lang="ts">
/**
 * 管理入口区域(右上角隐形式触发)
 *
 * 交互流程:
 * 1. 默认全屏运行模式下,窗口右上角有一个透明点击区
 * 2. 连续点击 6 下(2 秒内)→ 弹出管理员登录框(账号 + 密码)
 * 3. 调用后端 /api/admin/login 验证(复用现有接口,安全由后端保证)
 * 4. 验证通过 → 弹出操作菜单,三个按钮:
 *    - 配置模式:退出全屏 → 显示窗口装饰 → 跳转 /settings
 *    - 退出:退出整个应用
 *    - 取消:关闭弹窗,回到运行模式
 */
import { ref, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { ShieldCheck, Lock, Settings, LogOut, X, Eye, EyeOff, Loader2, User } from 'lucide-vue-next'
import { loadConfig } from '@/api'
import { quitApp } from '@/api/shellApi'

const router = useRouter()

// ===== 6 次点击计数 =====
const CLICK_THRESHOLD = 6
const CLICK_WINDOW_MS = 2000
const HOT_ZONE_SIZE = 120

const clickCount = ref(0)
let lastClickAt = 0
let resetTimer: ReturnType<typeof setTimeout> | null = null

// ===== 登录弹窗 =====
const passwordVisible = ref(false)
const usernameInput = ref('')
const passwordInput = ref('')
const loginError = ref('')
const verifying = ref(false)
const showLoginModal = ref(false)

// ===== 操作菜单弹窗 =====
const showActionModal = ref(false)
const switching = ref(false)

// ===== 热区点击处理 =====
const onHotZoneClick = () => {
  const now = Date.now()
  if (now - lastClickAt > CLICK_WINDOW_MS) {
    clickCount.value = 1
  } else {
    clickCount.value += 1
  }
  lastClickAt = now

  if (resetTimer) clearTimeout(resetTimer)
  resetTimer = setTimeout(() => {
    clickCount.value = 0
  }, CLICK_WINDOW_MS + 200)

  if (clickCount.value >= CLICK_THRESHOLD) {
    clickCount.value = 0
    if (resetTimer) {
      clearTimeout(resetTimer)
      resetTimer = null
    }
    openLoginModal()
  }
}

// ===== 登录弹窗 =====
const openLoginModal = () => {
  usernameInput.value = ''
  passwordInput.value = ''
  passwordVisible.value = false
  loginError.value = ''
  verifying.value = false
  showLoginModal.value = true
}

const closeLoginModal = () => {
  if (verifying.value) return
  showLoginModal.value = false
}

/**
 * 调用后端 /api/admin/login 验证管理员账号密码。
 * 复用现有登录接口,密码安全由后端 BCrypt 保证,无需本地存哈希。
 * 使用终端绑定的 serverUrl 作为请求地址。
 */
const verifyLogin = async () => {
  if (verifying.value) return
  if (!usernameInput.value.trim() || !passwordInput.value) {
    loginError.value = '请输入管理员账号和密码'
    return
  }
  verifying.value = true
  loginError.value = ''
  try {
    // 从终端绑定配置读取服务器地址
    const cfg = loadConfig()
    const base = (cfg?.serverUrl || '').replace(/\/$/, '')
    const url = base ? `${base}/api/admin/login` : '/api/admin/login'

    const resp = await axios.post(url, {
      username: usernameInput.value.trim(),
      password: passwordInput.value,
    }, { timeout: 10000 })

    if (resp.data?.code === 200) {
      // 验证通过:打开操作菜单
      showLoginModal.value = false
      showActionModal.value = true
    } else {
      loginError.value = resp.data?.message || '账号或密码错误'
    }
  } catch (e: any) {
    loginError.value = e?.response?.data?.message || '验证失败,请检查网络或账号密码'
  } finally {
    verifying.value = false
  }
}

// ===== 操作菜单 =====
const closeActionModal = () => {
  if (switching.value) return
  showActionModal.value = false
}

const enterConfigMode = async () => {
  if (switching.value) return
  switching.value = true
  try {
    // 不切换窗口模式:保持原来的全屏/窗口状态进入配置页
    // 窗口模式的切换由配置页"终端运行设置"保存时触发(Python 端 on_config_updated 信号处理)
    showActionModal.value = false
    router.push('/settings')
  } catch (e) {
    console.error('[AdminEntryZone] 进入配置模式失败:', e)
  } finally {
    switching.value = false
  }
}

const onQuitApp = async () => {
  if (switching.value) return
  switching.value = true
  try {
    await quitApp()
  } catch (e) {
    console.error('[AdminEntryZone] 退出应用失败:', e)
    switching.value = false
  }
}

onBeforeUnmount(() => {
  if (resetTimer) clearTimeout(resetTimer)
})
</script>

<template>
  <!-- 右上角透明热区 -->
  <div class="admin-zone" @click="onHotZoneClick" />

  <!-- 管理员登录弹窗 -->
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="showLoginModal" class="modal__overlay">
        <div class="modal__panel">
          <button class="modal__close btn-press" aria-label="关闭" @click="closeLoginModal">
            <X :size="18" />
          </button>

          <div class="modal__icon modal__icon--info">
            <Lock :size="40" stroke-width="2" />
          </div>

          <h2 class="modal__title">管理员验证</h2>
          <p class="modal__desc">请输入管理员账号和密码以进入管理模式</p>

          <!-- 账号输入 -->
          <div class="input-field">
            <User :size="18" class="input-field__icon" />
            <input
              v-model="usernameInput"
              type="text"
              class="input-field__input"
              placeholder="管理员账号"
              autocomplete="off"
              @keyup.enter="verifyLogin"
            />
          </div>

          <!-- 密码输入 -->
          <div class="input-field">
            <Lock :size="18" class="input-field__icon" />
            <input
              v-model="passwordInput"
              :type="passwordVisible ? 'text' : 'password'"
              class="input-field__input input-field__input--password"
              placeholder="密码"
              autocomplete="off"
              @keyup.enter="verifyLogin"
            />
            <button
              type="button"
              class="input-field__toggle btn-press"
              :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
              @click="passwordVisible = !passwordVisible"
            >
              <EyeOff v-if="passwordVisible" :size="18" />
              <Eye v-else :size="18" />
            </button>
          </div>

          <div v-if="loginError" class="modal__error">
            {{ loginError }}
          </div>

          <div class="modal__actions">
            <button
              class="modal__btn modal__btn--cancel btn-press"
              :disabled="verifying"
              @click="closeLoginModal"
            >
              取消
            </button>
            <button
              class="modal__btn modal__btn--confirm btn-press"
              :disabled="verifying"
              @click="verifyLogin"
            >
              <Loader2 v-if="verifying" class="spinner" :size="18" />
              <ShieldCheck v-else :size="18" />
              {{ verifying ? '验证中...' : '验证' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- 操作菜单弹窗(三按钮) -->
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="showActionModal" class="modal__overlay">
        <div class="modal__panel">
          <button class="modal__close btn-press" aria-label="关闭" @click="closeActionModal">
            <X :size="18" />
          </button>

          <div class="modal__icon modal__icon--success">
            <ShieldCheck :size="40" stroke-width="2" />
          </div>

          <h2 class="modal__title">管理模式</h2>
          <p class="modal__desc">验证通过,请选择操作</p>

          <div class="action-grid">
            <button
              class="action-tile action-tile--primary btn-press"
              :disabled="switching"
              @click="enterConfigMode"
            >
              <Settings :size="32" class="action-tile__icon" />
              <span class="action-tile__name">配置模式</span>
              <span class="action-tile__desc">退出全屏,进入配置页</span>
            </button>

            <button
              class="action-tile action-tile--danger btn-press"
              :disabled="switching"
              @click="onQuitApp"
            >
              <LogOut :size="32" class="action-tile__icon" />
              <span class="action-tile__name">退出</span>
              <span class="action-tile__desc">关闭整个应用</span>
            </button>

            <button
              class="action-tile action-tile--cancel btn-press"
              :disabled="switching"
              @click="closeActionModal"
            >
              <X :size="32" class="action-tile__icon" />
              <span class="action-tile__name">取消</span>
              <span class="action-tile__desc">返回运行模式</span>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 右上角透明热区 */
.admin-zone {
  position: fixed;
  top: 0;
  right: 0;
  width: v-bind('HOT_ZONE_SIZE + "px"');
  height: v-bind('HOT_ZONE_SIZE + "px"');
  z-index: 100;
  background: transparent;
  cursor: default;
  pointer-events: auto;
}

/* 遮罩 */
.modal__overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(14, 17, 21, 0.55);
}

/* 弹窗面板 */
.modal__panel {
  position: relative;
  width: 100%;
  max-width: 480px;
  padding: 32px 28px 24px;
  background: var(--doubao-card);
  border-radius: var(--doubao-radius);
  border: 1px solid var(--doubao-border);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

/* 关闭按钮 */
.modal__close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.modal__close:hover {
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
}

/* 图标 */
.modal__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  margin-bottom: 16px;
}
.modal__icon--info {
  background: rgba(0, 101, 253, 0.1);
  color: var(--doubao-primary);
}
.modal__icon--success {
  background: rgba(7, 193, 96, 0.1);
  color: var(--doubao-success);
}

/* 标题与说明 */
.modal__title {
  margin: 0 0 8px;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
  line-height: 1.3;
}
.modal__desc {
  margin: 0 0 20px;
  font-size: var(--fs-base);
  color: var(--doubao-secondary-foreground);
  line-height: 1.5;
}

/* 输入框(带前缀图标) */
.input-field {
  position: relative;
  width: 100%;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}
.input-field__icon {
  position: absolute;
  left: 16px;
  color: var(--doubao-muted-foreground);
  pointer-events: none;
  z-index: 1;
}
.input-field__input {
  width: 100%;
  height: 56px;
  padding: 0 56px 0 46px;
  border-radius: var(--doubao-radius-sm);
  border: 1px solid var(--doubao-border);
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
  font-size: var(--fs-base);
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.input-field__input:focus {
  border-color: var(--doubao-primary);
  box-shadow: 0 0 0 3px rgba(0, 101, 253, 0.15);
}
.input-field__input--password {
  padding-right: 56px;
}
.input-field__toggle {
  position: absolute;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--doubao-muted-foreground);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.input-field__toggle:hover {
  background: var(--doubao-border);
  color: var(--doubao-foreground);
}

/* 错误提示 */
.modal__error {
  width: 100%;
  margin-bottom: 16px;
  padding: 10px 14px;
  border-radius: var(--doubao-radius-sm);
  background: rgba(239, 68, 68, 0.1);
  color: var(--doubao-destructive);
  font-size: var(--fs-sm);
  text-align: left;
}

/* 按钮组 */
.modal__actions {
  display: flex;
  gap: 12px;
  width: 100%;
}
.modal__btn {
  flex: 1;
  min-height: var(--touch-md);
  padding: 0 20px;
  border-radius: var(--doubao-radius-sm);
  border: none;
  font-size: var(--fs-base);
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  transition: opacity 0.15s ease, background 0.15s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.modal__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.modal__btn--cancel {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
}
.modal__btn--cancel:hover {
  background: var(--doubao-border);
}
.modal__btn--confirm {
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
}
.modal__btn--confirm:hover {
  opacity: 0.9;
}

/* 操作菜单(三按钮网格) */
.action-grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.action-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 18px 8px;
  border-radius: var(--doubao-radius);
  border: 1px solid var(--doubao-border);
  background: var(--doubao-muted);
  color: var(--doubao-foreground);
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.12s ease, background 0.15s ease, border-color 0.15s ease;
}
.action-tile:hover {
  transform: translateY(-2px);
}
.action-tile:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}
.action-tile--primary {
  background: rgba(0, 101, 253, 0.08);
  border-color: rgba(0, 101, 253, 0.3);
  color: var(--doubao-primary);
}
.action-tile--primary:hover {
  background: rgba(0, 101, 253, 0.15);
}
.action-tile--danger {
  background: rgba(239, 68, 68, 0.08);
  border-color: rgba(239, 68, 68, 0.3);
  color: var(--doubao-destructive);
}
.action-tile--danger:hover {
  background: rgba(239, 68, 68, 0.15);
}
.action-tile--cancel {
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
}
.action-tile--cancel:hover {
  background: var(--doubao-border);
}
.action-tile__icon {
  margin-bottom: 4px;
}
.action-tile__name {
  font-size: var(--fs-base);
  font-weight: 700;
}
.action-tile__desc {
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
  line-height: 1.4;
}

/* loading spinner */
.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 入场动画 */
.modal-enter-active { transition: opacity 0.2s ease; }
.modal-enter-active .modal__panel {
  transition: transform 0.25s cubic-bezier(0.34, 1.4, 0.64, 1), opacity 0.2s ease;
}
.modal-enter-from { opacity: 0; }
.modal-enter-from .modal__panel {
  opacity: 0;
}
.modal-leave-active { transition: opacity 0.15s ease; }
.modal-leave-to { opacity: 0; }

/* 竖屏/小屏适配 */
@media (max-width: 1280px) {
  .modal__panel {
    max-width: 420px;
    padding: 24px 20px 20px;
  }
  .modal__icon { width: 60px; height: 60px; }
  .modal__title { font-size: var(--fs-lg); }
  .action-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .action-tile {
    flex-direction: row;
    justify-content: flex-start;
    padding: 14px 18px;
  }
  .action-tile__icon { margin-bottom: 0; }
}
</style>
