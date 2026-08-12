<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { backupApi, storeApi, systemApi } from '@/api'
import type { BackupInfo, Store, SystemConfig } from '@/api'
import type { UploadFile } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  ElTable,
  ElTableColumn,
  ElButton,
  ElPagination,
  ElMessage,
  ElMessageBox,
  ElDialog,
  ElForm,
  ElFormItem,
  ElRadioGroup,
  ElRadio,
  ElSelect,
  ElOption,
  ElUpload,
  ElTag,
  ElSwitch,
  ElInputNumber,
  ElInput,
} from 'element-plus'
import {
  DatabaseBackup,
  Download,
  Upload,
  Trash2,
  RefreshCw,
  Clock,
  HardDrive,
  Building2,
  Settings,
} from 'lucide-vue-next'

const authStore = useAuthStore()
const isSuperAdmin = computed(() => authStore.isSuperAdmin)
const ownStoreId = computed(() => authStore.storeId)

const backups = ref<BackupInfo[]>([])
const loading = ref(false)
const restoring = ref<string | null>(null)
const deleting = ref<string | null>(null)
const downloading = ref<string | null>(null)

const page = ref(1)
const size = ref(10)

const stores = ref<Store[]>([])

/* ===== 字节大小格式化 ===== */
const formatBytes = (bytes: number): string => {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = bytes
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(i === 0 ? 0 : 2)} ${units[i]}`
}

const formatTime = (ts: number): string => {
  const d = new Date(ts)
  const pad = (x: number) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const displayTime = (b: BackupInfo): string => {
  if (b.lastModifiedText) return b.lastModifiedText
  if (b.lastModified) return formatTime(b.lastModified)
  if (b.createdTime) return b.createdTime
  return '—'
}

/* ===== 备份类型展示 ===== */
const typeText = (t?: string) => (t === 'store' ? '门店' : t === 'full' ? '全库' : '未知')
const typeTagType = (t?: string): 'success' | 'primary' | 'info' =>
  t === 'store' ? 'success' : t === 'full' ? 'primary' : 'info'

/* ===== 列表 ===== */
const fetchBackups = async () => {
  loading.value = true
  try {
    backups.value = await backupApi.list()
  } catch {
    /* 错误已由拦截器提示 */
  } finally {
    loading.value = false
  }
}

const fetchStores = async () => {
  if (!isSuperAdmin.value) return
  try {
    stores.value = await storeApi.list()
  } catch {
    /* 拦截器提示 */
  }
}

const totalSize = computed(() => backups.value.reduce((sum, b) => sum + (b.size || 0), 0))

const latestTime = computed(() => {
  if (!backups.value.length) return '无备份'
  const max = backups.value.reduce((m, b) => Math.max(m, b.lastModified || 0), 0)
  if (max) return formatTime(max)
  return backups.value[0]?.lastModifiedText || backups.value[0]?.createdTime || '无备份'
})

const pagedBackups = computed(() => {
  const start = (page.value - 1) * size.value
  return backups.value.slice(start, start + size.value)
})

/* ===== 创建备份 ===== */
const createDialogVisible = ref(false)
const createType = ref<'full' | 'store'>(isSuperAdmin.value ? 'full' : 'store')
const createStoreId = ref<number | undefined>(ownStoreId.value || undefined)
const createSaving = ref(false)

const openCreateDialog = () => {
  createType.value = isSuperAdmin.value ? 'full' : 'store'
  createStoreId.value = ownStoreId.value || undefined
  createDialogVisible.value = true
}

const createBackup = async () => {
  if (createType.value === 'store' && !createStoreId.value) {
    ElMessage.warning('请选择门店')
    return
  }
  createSaving.value = true
  try {
    const params = createType.value === 'full'
      ? { type: 'full' as const }
      : { type: 'store' as const, storeId: createStoreId.value }
    const res = await backupApi.create(params)
    ElMessage.success(
      `备份成功:${res.name}(${typeText(res.type)},共 ${res.totalRows ?? 0} 行 / ${res.tableCount ?? 0} 表)`
    )
    createDialogVisible.value = false
    page.value = 1
    await fetchBackups()
  } catch {
    /* 拦截器提示 */
  } finally {
    createSaving.value = false
  }
}

/* ===== 恢复 ===== */
const restoreBackup = async (backup: BackupInfo) => {
  const scopeText = backup.type === 'store' && backup.storeName
    ? `门店「${backup.storeName}」的`
    : backup.type === 'store'
      ? '本门店的'
      : '全库'
  try {
    await ElMessageBox.confirm(
      `确定要恢复备份「${backup.name}」吗?此操作将覆盖${scopeText}所有数据,不可撤销。建议先下载备份留存。`,
      '恢复确认',
      { confirmButtonText: '确定恢复', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return /* 用户取消 */
  }
  restoring.value = backup.name
  try {
    const res = await backupApi.restore(backup.name)
    const r = res as { restoredRows?: number; restoredTables?: string[]; redactedAdminsSkipped?: number }
    ElMessage.success(
      `数据恢复成功${r.restoredRows != null ? `(共 ${r.restoredRows} 行 / ${(r.restoredTables || []).length} 表)` : ''},即将刷新页面`
    )
    if (r.redactedAdminsSkipped) {
      ElMessageBox.alert(
        `${r.redactedAdminsSkipped} 个管理员账号因密码敏感脱敏未随备份恢复,当前无法登录。请通过部署脚本重置超管密码后再登录。`,
        '管理员账号提醒',
        { confirmButtonText: '知道了', type: 'warning' }
      )
    }
    setTimeout(() => window.location.reload(), 1500)
  } catch {
    /* 拦截器提示 */
  } finally {
    restoring.value = null
  }
}

/* ===== 删除 ===== */
const deleteBackup = async (backup: BackupInfo) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除备份「${backup.name}」吗?删除后不可恢复。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return /* 用户取消 */
  }
  deleting.value = backup.name
  try {
    await backupApi.delete(backup.name)
    ElMessage.success('删除成功')
    const remaining = backups.value.length - 1
    const maxPage = Math.max(1, Math.ceil(remaining / size.value))
    if (page.value > maxPage) page.value = maxPage
    await fetchBackups()
  } catch {
    /* 拦截器提示 */
  } finally {
    deleting.value = null
  }
}

/* ===== 下载 ===== */
const downloadBackup = async (backup: BackupInfo) => {
  downloading.value = backup.name
  try {
    await backupApi.download(backup.name)
    ElMessage.success('开始下载')
  } catch {
    /* 拦截器提示 */
  } finally {
    downloading.value = null
  }
}

/* ===== 导入 ===== */
const importDialogVisible = ref(false)
const importFile = ref<File | null>(null)
const importRestore = ref(false)
const importing = ref(false)
const importResult = ref<{
  name: string
  type: string
  storeName?: string | null
  imported: boolean
  restored?: boolean
  restoredRows?: number
  redactedAdminsSkipped?: number
} | null>(null)

const openImportDialog = () => {
  importFile.value = null
  importRestore.value = false
  importResult.value = null
  importDialogVisible.value = true
}

const handleImportFileChange = (file: UploadFile) => {
  importFile.value = file.raw || null
  importResult.value = null
}

const handleImportFileRemove = () => {
  importFile.value = null
}

const confirmImport = async () => {
  if (!importFile.value) {
    ElMessage.warning('请选择备份文件(.json.gz)')
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const res = await backupApi.importBackup(importFile.value, importRestore.value)
    importResult.value = res
    ElMessage.success(importRestore.value && res.restored ? '导入并恢复成功' : '导入成功')
    await fetchBackups()
  } catch {
    /* 拦截器提示 */
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  fetchBackups()
  fetchStores()
})

/* ===== 备份配置弹窗 ===== */
const configDialogVisible = ref(false)
const configSaving = ref(false)
const configForm = ref({
  backup_auto_enabled: true,
  backup_auto_store_enabled: false,
  backup_keep_copies: 30,
  backup_keep_days: 30,
  backup_cron: '0 0 2 * * ?',
})

const getStr = (list: SystemConfig[], key: string, def = ''): string => {
  const item = list.find((c) => c.config_key === key)
  return item?.config_value ?? def
}
const getNum = (list: SystemConfig[], key: string, def = 0): number => {
  const n = Number(getStr(list, key))
  return isNaN(n) ? def : n
}
const getBool = (list: SystemConfig[], key: string, def = false): boolean => {
  const v = getStr(list, key).toLowerCase()
  if (v === 'true' || v === '1') return true
  if (v === 'false' || v === '0') return false
  return def
}

const fetchBackupConfig = async () => {
  try {
    const list = await systemApi.config()
    configForm.value = {
      backup_auto_enabled: getBool(list, 'backup_auto_enabled', true),
      backup_auto_store_enabled: getBool(list, 'backup_auto_store_enabled', false),
      backup_keep_copies: getNum(list, 'backup_keep_copies', 30),
      backup_keep_days: getNum(list, 'backup_keep_days', 30),
      backup_cron: getStr(list, 'backup_cron', '0 0 2 * * ?'),
    }
  } catch {
    /* 拦截器提示 */
  }
}

const openConfigDialog = () => {
  fetchBackupConfig()
  configDialogVisible.value = true
}

const saveBackupConfig = async () => {
  configSaving.value = true
  try {
    await systemApi.batchUpdateConfig([
      { key: 'backup_auto_enabled', value: String(configForm.value.backup_auto_enabled) },
      { key: 'backup_auto_store_enabled', value: String(configForm.value.backup_auto_store_enabled) },
      { key: 'backup_keep_copies', value: String(configForm.value.backup_keep_copies) },
      { key: 'backup_keep_days', value: String(configForm.value.backup_keep_days) },
      { key: 'backup_cron', value: configForm.value.backup_cron },
    ])
    ElMessage.success('备份配置已保存')
    configDialogVisible.value = false
  } catch {
    /* 拦截器提示 */
  } finally {
    configSaving.value = false
  }
}
</script>

<template>
  <Layout>
    <PageContainer title="数据库备份与恢复" description="企业级备份管理:支持全库/门店级备份、定时备份、导入导出与恢复。">
      <template #actions>
        <ElButton :icon="RefreshCw" :loading="loading" @click="fetchBackups">刷新</ElButton>
        <ElButton v-if="isSuperAdmin" :icon="Settings" @click="openConfigDialog">备份配置</ElButton>
        <ElButton :icon="Upload" @click="openImportDialog">导入备份</ElButton>
        <ElButton type="primary" :icon="DatabaseBackup" @click="openCreateDialog">立即备份</ElButton>
      </template>

      <!-- 统计卡片 -->
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <StatCard title="备份总数" :value="backups.length" :icon="DatabaseBackup" color="primary" />
        <StatCard title="总占用空间" :value="formatBytes(totalSize)" :icon="HardDrive" color="success" />
        <StatCard title="最近备份时间" :value="latestTime" :icon="Clock" color="warning" />
      </div>

      <!-- 备份列表 -->
      <div
        class="mt-6 overflow-hidden rounded-xl border border-border bg-card shadow-sm"
        v-loading="loading"
      >
        <ElTable :data="pagedBackups" style="width: 100%" row-key="name">
          <ElTableColumn label="备份文件" min-width="280">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <DatabaseBackup class="h-4 w-4 shrink-0 text-primary" />
                <span class="break-all text-sm font-medium text-text">{{ row.name }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="类型" width="100" align="center">
            <template #default="{ row }">
              <ElTag :type="typeTagType(row.type)" size="small">{{ typeText(row.type) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="门店" width="160" align="left">
            <template #default="{ row }">
              <span v-if="row.type === 'full'" class="text-text-muted">—</span>
              <span v-else class="text-text-secondary">{{ row.storeName || `#${row.storeId}` }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="表/行数" width="120" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">
                {{ row.tableCount ?? '—' }} / {{ row.totalRows ?? '—' }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="大小" width="120" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ formatBytes(row.size) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="备份时间" width="170" align="left">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ displayTime(row as BackupInfo) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="260" fixed="right" align="left">
            <template #default="{ row }">
              <div class="flex items-center gap-1">
                <ElButton
                  link
                  type="primary"
                  :icon="Download"
                  :loading="downloading === row.name"
                  @click="downloadBackup(row as BackupInfo)"
                >
                  下载
                </ElButton>
                <ElButton
                  link
                  type="warning"
                  :icon="Upload"
                  :loading="restoring === row.name"
                  @click="restoreBackup(row as BackupInfo)"
                >
                  恢复
                </ElButton>
                <ElButton
                  link
                  type="danger"
                  :icon="Trash2"
                  :loading="deleting === row.name"
                  @click="deleteBackup(row as BackupInfo)"
                >
                  删除
                </ElButton>
              </div>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState
              :icon="DatabaseBackup"
              description="暂无备份记录,点击「立即备份」创建第一个备份"
            />
          </template>
        </ElTable>

        <div
          v-if="backups.length > size"
          class="flex justify-end border-t border-border px-4 py-3"
        >
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="backups.length"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            background
          />
        </div>
      </div>

      <!-- 使用说明 -->
      <div class="mt-6 rounded-xl border border-border bg-card p-6 shadow-sm">
        <h3 class="mb-4 text-sm font-semibold text-text">使用说明</h3>
        <div class="space-y-2 text-sm text-text-secondary">
          <p>
            • <span class="font-medium text-text">立即备份</span>:
            超级管理员可备份全库;门店管理员只能备份本门店数据(包含菜品/菜单/订单/员工等)。
          </p>
          <p>
            • <span class="font-medium text-text">定时备份</span>:
            在「系统设置 → 备份配置」中可启用定时备份、调整 Cron 表达式与保留份数,系统会自动按计划备份全库。
          </p>
          <p>
            • <span class="font-medium text-text">下载</span>:
            将备份文件下载到本地留存,格式为 .json.gz(JSON+GZIP 压缩)。
          </p>
          <p>
            • <span class="font-medium text-text">导入</span>:
            上传 .json.gz 备份文件导入到系统,可选择导入后立即恢复。
          </p>
          <p>
            • <span class="font-medium text-text">恢复</span>:
            从备份文件恢复数据,会覆盖对应范围(全库或指定门店)的所有数据,操作前请先下载备份。
          </p>
          <p>
            • <span class="font-medium text-text">多租户隔离</span>:
            门店管理员仅能看到、下载、恢复、删除本门店的备份;超级管理员可操作所有备份。
          </p>
        </div>
      </div>
    </PageContainer>

    <!-- 创建备份弹窗 -->
    <ElDialog
      v-model="createDialogVisible"
      title="创建备份"
      width="460px"
      :close-on-click-modal="false"
      append-to-body
      destroy-on-close
    >
      <ElForm label-width="100px">
        <ElFormItem label="备份范围">
          <ElRadioGroup v-model="createType" :disabled="!isSuperAdmin">
            <ElRadio value="full" :disabled="!isSuperAdmin">全库备份</ElRadio>
            <ElRadio value="store">门店备份</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="createType === 'store'" label="选择门店">
          <ElSelect
            v-model="createStoreId"
            placeholder="请选择门店"
            class="w-full"
            :disabled="!isSuperAdmin"
          >
            <ElOption
              v-for="s in stores"
              :key="s.id"
              :label="s.name"
              :value="s.id as number"
            />
          </ElSelect>
          <div v-if="!isSuperAdmin" class="mt-1 text-xs text-text-muted">
            <Building2 class="mr-1 inline h-3 w-3" />门店管理员仅可备份本门店
          </div>
        </ElFormItem>
        <ElFormItem label="包含内容">
          <div class="text-xs leading-6 text-text-muted">
            <div v-if="createType === 'full'">全库:门店、管理员、部门、菜品、菜单、订单、通知、充值记录等</div>
            <div v-else>门店:本门店的部门、菜品、菜品分类、员工、菜单、菜单项、就餐时段、通知、订单、订单明细、充值记录</div>
          </div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="createSaving" @click="createBackup">开始备份</ElButton>
      </template>
    </ElDialog>

    <!-- 导入备份弹窗 -->
    <ElDialog
      v-model="importDialogVisible"
      title="导入备份"
      width="540px"
      :close-on-click-modal="false"
      append-to-body
      destroy-on-close
    >
      <div class="space-y-4">
        <div class="rounded-lg bg-bg-secondary px-4 py-3 text-sm text-text-secondary">
          <div class="mb-1 font-medium text-text">导入说明</div>
          <ul class="list-disc space-y-1 pl-5 text-xs">
            <li>仅支持系统导出的 .json.gz 备份文件。</li>
            <li>门店管理员只能导入本门店的备份文件。</li>
            <li>勾选「导入后立即恢复」将覆盖当前对应范围的数据,请谨慎操作。</li>
            <li>大文件导入可能需要较长时间,请耐心等待。</li>
          </ul>
        </div>

        <ElUpload
          :auto-upload="false"
          :limit="1"
          accept=".json.gz"
          :on-change="handleImportFileChange"
          :on-remove="handleImportFileRemove"
          drag
        >
          <div class="flex flex-col items-center gap-2 py-4">
            <Upload class="h-8 w-8 text-text-muted" />
            <div class="text-sm text-text">将备份文件拖到此处,或<span class="text-primary">点击上传</span></div>
            <div class="text-xs text-text-muted">仅支持 .json.gz 格式</div>
          </div>
        </ElUpload>

        <ElForm label-width="120px">
          <ElFormItem label="导入后立即恢复">
            <ElSwitch v-model="importRestore" />
            <span class="ml-3 text-xs text-text-muted">勾选后将覆盖当前对应范围数据</span>
          </ElFormItem>
        </ElForm>

        <div v-if="importResult" class="rounded-lg border border-border p-4">
          <div class="mb-2 flex items-center gap-4 text-sm">
            <span class="text-success">导入成功</span>
            <ElTag :type="typeTagType(importResult.type)" size="small">{{ typeText(importResult.type) }}</ElTag>
            <span v-if="importResult.storeName" class="text-text-secondary">{{ importResult.storeName }}</span>
            <span v-if="importResult.restored" class="text-warning">
              已恢复 {{ importResult.restoredRows ?? 0 }} 行
            </span>
          </div>
          <div
            v-if="importResult.restored && importResult.redactedAdminsSkipped"
            class="mt-2 rounded bg-warning/10 px-3 py-2 text-xs text-warning"
          >
            有 {{ importResult.redactedAdminsSkipped }} 个管理员账号因密码脱敏未随备份恢复,需通过部署脚本重置密码后登录。
          </div>
          <div class="text-xs text-text-muted">文件名:{{ importResult.name }}</div>
        </div>
      </div>
      <template #footer>
        <ElButton @click="importDialogVisible = false">关闭</ElButton>
        <ElButton
          type="primary"
          :loading="importing"
          :disabled="!importFile"
          @click="confirmImport"
        >
          确认导入
        </ElButton>
      </template>
    </ElDialog>

    <!-- 备份配置弹窗 -->
    <ElDialog
      v-model="configDialogVisible"
      title="备份配置"
      width="560px"
      :close-on-click-modal="false"
      append-to-body
      destroy-on-close
    >
      <ElForm :model="configForm" label-width="140px" label-position="right">
        <ElFormItem label="启用定时备份">
          <ElSwitch v-model="configForm.backup_auto_enabled" />
        </ElFormItem>
        <ElFormItem label="门店级定时备份">
          <ElSwitch v-model="configForm.backup_auto_store_enabled" />
          <span class="ml-3 text-xs text-text-muted">开启后随定时备份为每个门店额外生成门店级备份</span>
        </ElFormItem>
        <ElFormItem label="备份保留份数">
          <ElInputNumber v-model="configForm.backup_keep_copies" :min="1" :max="365" />
          <span class="ml-3 text-xs text-text-muted">超出份数自动清理最旧备份</span>
        </ElFormItem>
        <ElFormItem label="备份保留天数">
          <ElInputNumber v-model="configForm.backup_keep_days" :min="1" :max="3650" />
          <span class="ml-3 text-xs text-text-muted">超出天数自动清理(兼容旧配置)</span>
        </ElFormItem>
        <ElFormItem label="定时备份 Cron">
          <ElInput v-model="configForm.backup_cron" placeholder="如 0 0 2 * * ?" style="width: 220px" />
          <span class="ml-3 text-xs text-text-muted">默认每天凌晨 2 点</span>
        </ElFormItem>
      </ElForm>
      <div class="mb-4 rounded-lg bg-bg-secondary px-4 py-3 text-xs text-text-muted">
        <div class="font-medium text-text">Cron 说明</div>
        <ul class="mt-1 list-disc space-y-1 pl-5">
          <li>格式:秒 分 时 日 月 周(6 段,Spring CronExpression)</li>
          <li>每天凌晨 2 点:<code class="rounded bg-bg-tertiary px-1">0 0 2 * * ?</code></li>
          <li>每小时整点:<code class="rounded bg-bg-tertiary px-1">0 0 * * * ?</code></li>
          <li>工作日凌晨 3 点:<code class="rounded bg-bg-tertiary px-1">0 0 3 ? * MON-FRI</code></li>
        </ul>
      </div>
      <template #footer>
        <div class="flex justify-end gap-3">
          <ElButton @click="configDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="configSaving" @click="saveBackupConfig">保存配置</ElButton>
        </div>
      </template>
    </ElDialog>
  </Layout>
</template>
