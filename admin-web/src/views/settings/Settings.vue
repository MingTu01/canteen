<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { storeApi, systemApi, adminApi } from '@/api'
import type { Store, SystemConfig, SystemHealth, SystemVersion } from '@/api'
import {
  ElTabs,
  ElTabPane,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElButton,
  ElSwitch,
  ElSelect,
  ElOption,
  ElDescriptions,
  ElDescriptionsItem,
  ElTag,
  ElMessage,
} from 'element-plus'
import {
  Store as StoreIcon,
  ShieldCheck,
  Settings as SettingsIcon,
  Save,
  KeyRound,
  Wallet,
  Bell,
  Info,
} from 'lucide-vue-next'

const authStore = useAuthStore()
const adminId = computed(() => authStore.admin?.id)
const isSuperAdmin = computed(() => authStore.isSuperAdmin)
const ownStoreId = computed(() => authStore.storeId)

const activeTab = ref('store')

/* ============ 门店信息 ============ */
const stores = ref<Store[]>([])
const editingStoreId = ref<number | undefined>(ownStoreId.value || undefined)
const storeForm = reactive<Store>({
  name: '',
  address: '',
  phone: '',
})
const storeLoading = ref(false)
const storeSaving = ref(false)

const fetchStores = async () => {
  if (!isSuperAdmin.value) return
  try {
    stores.value = await storeApi.list()
    if (!editingStoreId.value && stores.value.length) {
      editingStoreId.value = stores.value[0].id
    }
  } catch {
    /* 拦截器提示 */
  }
}

const fetchStoreInfo = async () => {
  const id = editingStoreId.value
  if (!id) return
  storeLoading.value = true
  try {
    const s = await storeApi.get(id)
    storeForm.name = s.name || ''
    storeForm.address = s.address || ''
    storeForm.phone = s.phone || ''
  } catch {
    /* 拦截器提示 */
  } finally {
    storeLoading.value = false
  }
}

const saveStore = async () => {
  const id = editingStoreId.value
  if (!id) {
    ElMessage.warning('请选择门店')
    return
  }
  if (!storeForm.name?.trim()) {
    ElMessage.warning('请输入门店名称')
    return
  }
  storeSaving.value = true
  try {
    await storeApi.update(id, {
      id,
      name: storeForm.name.trim(),
      address: storeForm.address?.trim() || '',
      phone: storeForm.phone?.trim() || '',
    })
    ElMessage.success('门店信息已保存')
  } catch {
    /* 拦截器提示 */
  } finally {
    storeSaving.value = false
  }
}

const onStoreChange = () => fetchStoreInfo()

/* ============ 配置通用逻辑 ============ */
const configMap = ref<Record<string, SystemConfig>>({})
const configLoading = ref(false)

const fetchConfigs = async () => {
  configLoading.value = true
  try {
    const list = await systemApi.config()
    const map: Record<string, SystemConfig> = {}
    list.forEach((c) => {
      map[c.config_key] = c
    })
    configMap.value = map
  } catch {
    /* 拦截器提示 */
  } finally {
    configLoading.value = false
  }
}

const getStr = (key: string, def = ''): string => {
  const v = configMap.value[key]?.config_value
  return v === undefined || v === null ? def : String(v)
}
const getNum = (key: string, def = 0): number => {
  const v = Number(getStr(key, String(def)))
  return Number.isFinite(v) ? v : def
}
const getBool = (key: string, def = false): boolean => {
  const v = getStr(key, def ? 'true' : 'false').toLowerCase()
  return v === 'true' || v === '1'
}

/* ============ 支付与余额 ============ */
const payForm = reactive({
  balance_min_warning: 50,
  allow_negative_balance: false,
  recharge_min_amount: 1,
  recharge_max_amount: 5000,
})
const paySaving = ref(false)

const syncPayForm = () => {
  payForm.balance_min_warning = getNum('balance_min_warning', 50)
  payForm.allow_negative_balance = getBool('allow_negative_balance', false)
  payForm.recharge_min_amount = getNum('recharge_min_amount', 1)
  payForm.recharge_max_amount = getNum('recharge_max_amount', 5000)
}
const savePayConfig = async () => {
  if (!isSuperAdmin.value) {
    ElMessage.warning('仅超级管理员可修改系统配置')
    return
  }
  paySaving.value = true
  try {
    await systemApi.batchUpdateConfig([
      { key: 'balance_min_warning', value: String(payForm.balance_min_warning) },
      { key: 'allow_negative_balance', value: String(payForm.allow_negative_balance) },
      { key: 'recharge_min_amount', value: String(payForm.recharge_min_amount) },
      { key: 'recharge_max_amount', value: String(payForm.recharge_max_amount) },
    ])
    ElMessage.success('支付与余额配置已保存')
    await fetchConfigs()
  } catch {
    /* 拦截器提示 */
  } finally {
    paySaving.value = false
  }
}

/* ============ 通知配置 ============ */
const notifyForm = reactive({
  notification_default_duration_days: 30,
  notification_auto_expire: true,
})
const notifySaving = ref(false)

const syncNotifyForm = () => {
  notifyForm.notification_default_duration_days = getNum('notification_default_duration_days', 30)
  notifyForm.notification_auto_expire = getBool('notification_auto_expire', true)
}
const saveNotifyConfig = async () => {
  if (!isSuperAdmin.value) {
    ElMessage.warning('仅超级管理员可修改系统配置')
    return
  }
  notifySaving.value = true
  try {
    await systemApi.batchUpdateConfig([
      { key: 'notification_default_duration_days', value: String(notifyForm.notification_default_duration_days) },
      { key: 'notification_auto_expire', value: String(notifyForm.notification_auto_expire) },
    ])
    ElMessage.success('通知配置已保存')
    await fetchConfigs()
  } catch {
    /* 拦截器提示 */
  } finally {
    notifySaving.value = false
  }
}

/* ============ 账户安全 ============ */
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const pwdSaving = ref(false)

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value === pwdForm.oldPassword) {
          callback(new Error('新密码不能与旧密码相同'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value !== pwdForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

const changePassword = async () => {
  if (!pwdFormRef.value) return
  const id = adminId.value
  if (!id) {
    ElMessage.error('未获取到管理员信息')
    return
  }
  await pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    pwdSaving.value = true
    try {
      await adminApi.changePassword(id, {
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword,
      })
      ElMessage.success('密码修改成功')
      pwdForm.oldPassword = ''
      pwdForm.newPassword = ''
      pwdForm.confirmPassword = ''
    } catch {
      /* 拦截器提示 */
    } finally {
      pwdSaving.value = false
    }
  })
}

/* ============ 系统信息 ============ */
const version = ref<SystemVersion | null>(null)
const health = ref<SystemHealth | null>(null)
const sysLoading = ref(false)

const fetchSysInfo = async () => {
  sysLoading.value = true
  try {
    const [v, h] = await Promise.all([
      systemApi.version().catch(() => null),
      systemApi.health().catch(() => null),
    ])
    version.value = v
    health.value = h
  } finally {
    sysLoading.value = false
  }
}

const migrations = computed(() => {
  const m = version.value?.migrations as Array<Record<string, unknown>> | undefined
  return Array.isArray(m) ? m : []
})

const refreshSysInfo = () => fetchSysInfo()

/** 格式化字节大小 */
const fmtBytes = (bytes?: number): string => {
  if (bytes == null || bytes < 0) return '—'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i]
}

/** 使用率对应的 ElTag 类型 */
const usageTagType = (percent?: number): 'success' | 'warning' | 'danger' => {
  if (percent == null) return 'success'
  if (percent >= 90) return 'danger'
  if (percent >= 70) return 'warning'
  return 'success'
}

onMounted(async () => {
  await fetchStores()
  if (editingStoreId.value) await fetchStoreInfo()
  await fetchConfigs()
  syncPayForm()
  syncNotifyForm()
  await fetchSysInfo()
})
</script>

<template>
  <Layout>
    <PageContainer title="系统设置" description="管理门店信息、订餐规则、支付、通知、备份与账户安全。">
      <ElTabs v-model="activeTab" class="rounded-xl border border-border bg-card p-5 shadow-sm">
        <!-- 门店信息 -->
        <ElTabPane name="store">
          <template #label>
            <span class="flex items-center gap-1.5">
              <StoreIcon class="h-4 w-4" /> 门店信息
            </span>
          </template>

          <div v-loading="storeLoading" class="max-w-2xl">
            <ElFormItem v-if="isSuperAdmin" label="选择门店" label-width="100px" class="mb-4">
              <ElSelect v-model="editingStoreId" placeholder="选择门店" style="width: 260px" @change="onStoreChange">
                <ElOption v-for="s in stores" :key="s.id" :label="s.name" :value="s.id as number" />
              </ElSelect>
            </ElFormItem>

            <ElForm :model="storeForm" label-width="100px" label-position="right">
              <ElFormItem label="门店名称" required>
                <ElInput v-model="storeForm.name" placeholder="请输入门店名称" maxlength="50" />
              </ElFormItem>
              <ElFormItem label="门店地址">
                <ElInput v-model="storeForm.address" placeholder="请输入门店地址" maxlength="120" />
              </ElFormItem>
              <ElFormItem label="联系电话">
                <ElInput v-model="storeForm.phone" placeholder="请输入联系电话" maxlength="20" />
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :icon="Save" :loading="storeSaving" @click="saveStore">保存信息</ElButton>
              </ElFormItem>
            </ElForm>
          </div>
        </ElTabPane>

        <!-- 支付与余额 -->
        <ElTabPane name="pay">
          <template #label>
            <span class="flex items-center gap-1.5">
              <Wallet class="h-4 w-4" /> 支付与余额
            </span>
          </template>

          <div v-loading="configLoading" class="max-w-2xl">
            <ElForm :model="payForm" label-width="140px" label-position="right">
              <ElFormItem label="余额预警值">
                <ElInputNumber v-model="payForm.balance_min_warning" :min="0" :precision="2" :step="10" class="w-48" />
                <span class="ml-3 text-xs text-text-muted">余额低于此值时提醒员工充值</span>
              </ElFormItem>
              <ElFormItem label="允许负余额消费">
                <ElSwitch v-model="payForm.allow_negative_balance" />
                <span class="ml-3 text-xs text-text-muted">关闭后余额不足无法下单</span>
              </ElFormItem>
              <ElFormItem label="最小充值金额">
                <ElInputNumber v-model="payForm.recharge_min_amount" :min="0" :precision="2" :step="10" class="w-48" />
              </ElFormItem>
              <ElFormItem label="最大充值金额">
                <ElInputNumber v-model="payForm.recharge_max_amount" :min="0" :precision="2" :step="100" class="w-48" />
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :icon="Save" :loading="paySaving" @click="savePayConfig">
                  保存支付配置
                </ElButton>
              </ElFormItem>
            </ElForm>
          </div>
        </ElTabPane>

        <!-- 通知配置 -->
        <ElTabPane name="notify">
          <template #label>
            <span class="flex items-center gap-1.5">
              <Bell class="h-4 w-4" /> 通知配置
            </span>
          </template>

          <div v-loading="configLoading" class="max-w-2xl">
            <ElForm :model="notifyForm" label-width="160px" label-position="right">
              <ElFormItem label="默认通知有效天数">
                <ElInputNumber v-model="notifyForm.notification_default_duration_days" :min="1" :max="365" class="w-48" />
                <span class="ml-3 text-xs text-text-muted">新建通知默认上架一个月</span>
              </ElFormItem>
              <ElFormItem label="到期自动下架">
                <ElSwitch v-model="notifyForm.notification_auto_expire" />
                <span class="ml-3 text-xs text-text-muted">系统每分钟检查并下架过期通知</span>
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :icon="Save" :loading="notifySaving" @click="saveNotifyConfig">
                  保存通知配置
                </ElButton>
              </ElFormItem>
            </ElForm>
          </div>
        </ElTabPane>

        <!-- 账户安全 -->
        <ElTabPane name="security">
          <template #label>
            <span class="flex items-center gap-1.5">
              <ShieldCheck class="h-4 w-4" /> 账户安全
            </span>
          </template>

          <div class="max-w-md">
            <div class="mb-5 flex items-center gap-3 rounded-xl border border-border-light bg-bg-secondary px-4 py-3">
              <div class="flex h-10 w-10 items-center justify-center rounded-full bg-primary-50">
                <KeyRound class="h-5 w-5 text-primary" />
              </div>
              <div>
                <div class="text-sm font-medium text-text">{{ authStore.admin?.name || '管理员' }}</div>
                <div class="text-xs text-text-muted">{{ authStore.admin?.username }}</div>
              </div>
            </div>

            <ElForm
              ref="pwdFormRef"
              :model="pwdForm"
              :rules="pwdRules"
              label-width="100px"
              label-position="right"
            >
              <ElFormItem label="旧密码" prop="oldPassword">
                <ElInput v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入旧密码" />
              </ElFormItem>
              <ElFormItem label="新密码" prop="newPassword">
                <ElInput v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 8 位" />
              </ElFormItem>
              <ElFormItem label="确认密码" prop="confirmPassword">
                <ElInput v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :icon="KeyRound" :loading="pwdSaving" @click="changePassword">修改密码</ElButton>
              </ElFormItem>
            </ElForm>
          </div>
        </ElTabPane>

        <!-- 系统信息 -->
        <ElTabPane name="sysinfo">
          <template #label>
            <span class="flex items-center gap-1.5">
              <Info class="h-4 w-4" /> 系统信息
            </span>
          </template>

          <div v-loading="sysLoading" class="max-w-3xl space-y-4">
            <div class="flex items-center justify-between">
              <h3 class="text-sm font-medium text-text">系统版本与健康状态</h3>
              <ElButton size="small" :icon="SettingsIcon" @click="refreshSysInfo">刷新</ElButton>
            </div>

            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="系统版本">
                <ElTag type="success" size="small">{{ version?.version || '—' }}</ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="构建时间">{{ version?.buildTime || '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="系统描述">{{ version?.description || '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="最新迁移版本">{{ version?.latestMigration || '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="服务状态">
                <ElTag :type="health?.status === 'UP' ? 'success' : 'danger'" size="small">
                  {{ health?.status || '—' }}
                </ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="数据库状态">
                <ElTag :type="health?.database === 'UP' ? 'success' : 'danger'" size="small">
                  {{ health?.database || '—' }}
                </ElTag>
              </ElDescriptionsItem>
            </ElDescriptions>

            <!-- 系统资源监控 -->
            <div class="space-y-4">
              <h3 class="text-sm font-medium text-text">系统资源监控</h3>
              <!-- CPU 占用 -->
              <div class="rounded-lg border border-border-light p-4">
                <div class="mb-2 flex items-center justify-between">
                  <span class="text-sm font-medium text-text">CPU 占用</span>
                  <ElTag :type="usageTagType(health?.cpuUsagePercent)" size="small">
                    {{ health?.cpuUsagePercent != null ? health.cpuUsagePercent.toFixed(1) + '%' : '—' }}
                  </ElTag>
                </div>
                <div class="h-2.5 w-full overflow-hidden rounded-full bg-bg-tertiary">
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    :style="{
                      width: Math.min(health?.cpuUsagePercent ?? 0, 100) + '%',
                      background: (health?.cpuUsagePercent ?? 0) >= 90 ? '#ef4444' : (health?.cpuUsagePercent ?? 0) >= 70 ? '#f59e0b' : '#10b981'
                    }"
                  ></div>
                </div>
                <div class="mt-1.5 flex justify-between text-xs text-text-muted">
                  <span>进程 CPU: {{ health?.processCpuUsagePercent != null ? health.processCpuUsagePercent.toFixed(1) + '%' : '—' }}</span>
                  <span>核心数: {{ health?.availableProcessors ?? '—' }}</span>
                </div>
              </div>

              <!-- 内存占用 -->
              <div class="rounded-lg border border-border-light p-4">
                <div class="mb-2 flex items-center justify-between">
                  <span class="text-sm font-medium text-text">系统内存占用</span>
                  <ElTag :type="usageTagType(health?.systemMemoryUsagePercent)" size="small">
                    {{ health?.systemMemoryUsagePercent != null ? health.systemMemoryUsagePercent.toFixed(1) + '%' : '—' }}
                  </ElTag>
                </div>
                <div class="h-2.5 w-full overflow-hidden rounded-full bg-bg-tertiary">
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    :style="{
                      width: Math.min(health?.systemMemoryUsagePercent ?? 0, 100) + '%',
                      background: (health?.systemMemoryUsagePercent ?? 0) >= 90 ? '#ef4444' : (health?.systemMemoryUsagePercent ?? 0) >= 70 ? '#f59e0b' : '#10b981'
                    }"
                  ></div>
                </div>
                <div class="mt-1.5 flex justify-between text-xs text-text-muted">
                  <span>已用: {{ fmtBytes(health?.systemUsedMemory) }} / 总: {{ fmtBytes(health?.systemTotalMemory) }}</span>
                </div>
              </div>

              <!-- JVM 内存 -->
              <div class="rounded-lg border border-border-light p-4">
                <div class="mb-2 flex items-center justify-between">
                  <span class="text-sm font-medium text-text">JVM 内存占用</span>
                  <ElTag :type="usageTagType(health?.jvmMemoryUsagePercent)" size="small">
                    {{ health?.jvmMemoryUsagePercent != null ? health.jvmMemoryUsagePercent.toFixed(1) + '%' : '—' }}
                  </ElTag>
                </div>
                <div class="h-2.5 w-full overflow-hidden rounded-full bg-bg-tertiary">
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    :style="{
                      width: Math.min(health?.jvmMemoryUsagePercent ?? 0, 100) + '%',
                      background: (health?.jvmMemoryUsagePercent ?? 0) >= 90 ? '#ef4444' : (health?.jvmMemoryUsagePercent ?? 0) >= 70 ? '#f59e0b' : '#10b981'
                    }"
                  ></div>
                </div>
                <div class="mt-1.5 flex justify-between text-xs text-text-muted">
                  <span>已用: {{ fmtBytes(health?.jvmUsedMemory) }} / 最大: {{ fmtBytes(health?.jvmMaxMemory) }}</span>
                </div>
              </div>

              <!-- 硬盘占用 -->
              <div class="rounded-lg border border-border-light p-4">
                <div class="mb-2 flex items-center justify-between">
                  <span class="text-sm font-medium text-text">硬盘占用</span>
                  <ElTag :type="usageTagType(health?.diskUsagePercent)" size="small">
                    {{ health?.diskUsagePercent != null ? health.diskUsagePercent.toFixed(1) + '%' : '—' }}
                  </ElTag>
                </div>
                <div class="h-2.5 w-full overflow-hidden rounded-full bg-bg-tertiary">
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    :style="{
                      width: Math.min(health?.diskUsagePercent ?? 0, 100) + '%',
                      background: (health?.diskUsagePercent ?? 0) >= 90 ? '#ef4444' : (health?.diskUsagePercent ?? 0) >= 70 ? '#f59e0b' : '#10b981'
                    }"
                  ></div>
                </div>
                <div class="mt-1.5 flex justify-between text-xs text-text-muted">
                  <span>已用: {{ fmtBytes(health?.diskUsed) }} / 总: {{ fmtBytes(health?.diskTotal) }}</span>
                  <span>可用: {{ fmtBytes(health?.diskFree) }}</span>
                </div>
              </div>
            </div>

            <div v-if="migrations.length">
              <h3 class="mb-2 text-sm font-medium text-text">数据库迁移历史</h3>
              <ElDescriptions :column="4" border size="small">
                <ElDescriptionsItem
                  v-for="m in migrations"
                  :key="String(m.version)"
                  :label="String(m.version)"
                >
                  <span class="text-xs">{{ m.description }}</span>
                  <ElTag
                    :type="m.success ? 'success' : 'danger'"
                    size="small"
                    class="ml-2"
                  >
                    {{ m.success ? '成功' : '失败' }}
                  </ElTag>
                </ElDescriptionsItem>
              </ElDescriptions>
            </div>

            <div v-if="!version && !sysLoading">
              <EmptyState description="暂无系统信息" />
            </div>
          </div>
        </ElTabPane>
      </ElTabs>
    </PageContainer>
  </Layout>
</template>
