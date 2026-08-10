<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElImage,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import type { FormInstance, FormRules, UploadFile } from 'element-plus'
import { Plus, Pencil, Trash2, Wallet, UserCircle2, Power, PowerOff, Upload, Download, ClipboardList, AlertTriangle, FileSpreadsheet, ImagePlus, Info } from 'lucide-vue-next'
import * as XLSX from 'xlsx'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import { useCrud } from '@/composables/useCrud'
import { useAuthStore } from '@/stores/auth'
import { employeeApi, departmentApi, rechargeApi, orderApi } from '@/api'
import type { EmployeeImportResult, EmployeeImportRow, LowBalanceStats } from '@/api/employee'
import type { Department, Employee, Order, PageResult } from '@/api/types'
import { COMMON_STATUS, ORDER_STATUS, MEAL_TYPE } from '@/constants/dict'
import { formatMoney } from '@/utils/money'
import { compressImage } from '@/utils/imageCompress'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)

type EmployeeRow = Employee & { departmentName?: string; storeName?: string }

const keyword = ref('')
const departmentId = ref<number | undefined>(undefined)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const departments = ref<Department[]>([])

const deptName = (id?: number) => departments.value.find((d) => d.id === id)?.name

const { list: employees, loading, fetchList, handleDelete, dialogVisible, dialogLoading, isEdit } = useCrud<Employee>({
  list: async () => {
    const sidVal = sid.value
    if (!sidVal) return []
    const res = await employeeApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      keyword: keyword.value,
      departmentId: departmentId.value,
    })
    total.value = res.total ?? res.records.length
    return res.records as EmployeeRow[]
  },
  create: (d) => employeeApi.create(d),
  update: (id, d) => employeeApi.update(id, d),
  remove: (id) => employeeApi.delete(id),
  entityName: '员工',
})

const formRef = ref<FormInstance>()
const defaultEmployee = (): Employee => ({
  storeId: sid.value ?? 0,
  cardNo: '',
  phone: '',
  name: '',
  avatar: '',
  departmentId: undefined,
  balance: 5000,
  password: '',
  status: 1,
})
const form = ref<Employee>(defaultEmployee())

const rules: FormRules = {
  cardNo: [{ required: true, message: '请输入员工卡号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入员工姓名', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }],
}

const openAdd = () => {
  isEdit.value = false
  form.value = defaultEmployee()
  dialogVisible.value = true
}

const openEdit = (row: Employee) => {
  isEdit.value = true
  form.value = { ...row, password: '' }
  dialogVisible.value = true
}

// ===== 读卡器刷卡(读卡助手模拟键盘) =====
// 读卡助手(本地 32 位进程加载 OUR_IDR.dll)把卡号模拟成键盘输入+回车,
// 只要卡号输入框聚焦,刷卡就会自动填入卡号,前端无需任何接线。
// 本页不再检测读卡助手状态(检测逻辑已移至下载中心),
// 仅在卡号输入框旁显示静态提示,引导用户到右上角下载中心下载读卡助手。

const cardNoInput = ref()

/** 弹窗打开后:聚焦卡号输入框,刷卡即可填入 */
const onDialogOpened = async () => {
  nextTick(() => cardNoInput.value?.focus())
}

/** 弹窗关闭后:无需处理(读卡助手常驻后台) */
const onDialogClosed = () => {}

/** 启用/禁用员工(只更新 status 字段) */
const handleToggleStatus = async (row: Employee) => {
  if (!row.id) return
  const newStatus = row.status === 1 ? 0 : 1
  const action = newStatus === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定要${action}员工「${row.name}」吗？`, `${action}确认`, {
      type: 'warning',
      confirmButtonText: `确认${action}`,
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await employeeApi.update(row.id, { ...row, password: '', status: newStatus })
    ElMessage.success(`${action}成功`)
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  dialogLoading.value = true
  try {
    if (isEdit.value && form.value.id) {
      await employeeApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await employeeApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    dialogLoading.value = false
  }
}

// ===== 头像上传 =====
// 改用 ImageUploader 组件走后端上传 API,统一图片存储路径(/uploads/),
// 避免把 base64 dataURL 直接写进 MySQL 导致数据库膨胀、响应变大、图片无法独立缓存。
// v-model 直接绑定 form.avatar,ImageUploader 内部已处理压缩与上传。

// 充值
const DEFAULT_RECHARGE_AMOUNT = 3000
const rechargeVisible = ref(false)
const rechargeLoading = ref(false)
const rechargeEmployee = ref<Employee | null>(null)
const rechargeAmount = ref<number>(DEFAULT_RECHARGE_AMOUNT)

const openRecharge = (row: Employee) => {
  rechargeEmployee.value = row
  rechargeAmount.value = DEFAULT_RECHARGE_AMOUNT
  rechargeVisible.value = true
}

const confirmRecharge = async () => {
  if (!rechargeEmployee.value?.id) return
  if (!rechargeAmount.value || rechargeAmount.value <= 0) {
    ElMessage.warning('请输入有效的充值金额')
    return
  }
  rechargeLoading.value = true
  try {
    await rechargeApi.create({
      employeeId: rechargeEmployee.value.id,
      amount: rechargeAmount.value,
      storeId: rechargeEmployee.value.storeId,
      operator: authStore.admin?.name || authStore.admin?.username || 'admin',
    })
    ElMessage.success('充值成功')
    rechargeVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    rechargeLoading.value = false
  }
}

// ===== 批量充值 =====
const batchRechargeVisible = ref(false)
const batchRechargeLoading = ref(false)
const batchRechargeAmount = ref<number>(DEFAULT_RECHARGE_AMOUNT)

const openBatchRecharge = () => {
  batchRechargeAmount.value = DEFAULT_RECHARGE_AMOUNT
  batchRechargeVisible.value = true
}

const confirmBatchRecharge = async () => {
  if (!batchRechargeAmount.value || batchRechargeAmount.value <= 0) {
    ElMessage.warning('请输入有效的充值金额')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要给本食堂所有在职员工每人充值 ${batchRechargeAmount.value.toFixed(2)} 元吗？`,
      '批量充值确认',
      {
        type: 'warning',
        confirmButtonText: '确认充值',
        cancelButtonText: '取消',
      }
    )
  } catch {
    return
  }
  batchRechargeLoading.value = true
  try {
    const result = await employeeApi.batchRecharge({
      storeId: sid.value ?? undefined,
      amount: batchRechargeAmount.value,
    })
    ElMessage.success(`成功为 ${result.successCount} 名员工充值`)
    batchRechargeVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    batchRechargeLoading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  keyword.value = ''
  departmentId.value = undefined
  page.value = 1
  fetchList()
}

const handlePageChange = (p: number) => {
  page.value = p
  fetchList()
}

const fetchDepartments = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    departments.value = []
    return
  }
  try {
    departments.value = await departmentApi.list(sidVal)
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

// ===== 批量导入 =====
const importDialogVisible = ref(false)
const importLoading = ref(false)
const importResult = ref<EmployeeImportResult | null>(null)

/** 下载导入模板 */
const handleDownloadTemplate = () => {
  const data = [
    { 卡号: 'CARD001', 手机号: '13800000001', 姓名: '张三', 部门名称: '技术部', 初始余额: 5000, 密码: '', 状态: '启用', 头像URL: '' },
    { 卡号: 'CARD002', 手机号: '13800000002', 姓名: '李四', 部门名称: '市场部', 初始余额: 5000, 密码: '', 状态: '启用', 头像URL: 'https://example.com/avatar.png' },
  ]
  const ws = XLSX.utils.json_to_sheet(data)
  // 列宽
  ws['!cols'] = [{ wch: 16 }, { wch: 14 }, { wch: 12 }, { wch: 16 }, { wch: 10 }, { wch: 12 }, { wch: 8 }, { wch: 40 }]
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '员工导入模板')
  XLSX.writeFile(wb, '员工导入模板.xlsx')
}

/** 选择文件后解析并预览,然后调用 batchImport */
const handleImportFile = async (file: UploadFile) => {
  const raw = file.raw
  if (!raw) return
  importLoading.value = true
  importResult.value = null
  try {
    const buf = await raw.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    const firstSheetName = wb.SheetNames[0]
    const ws = wb.Sheets[firstSheetName]
    const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: '' })
    const employees: EmployeeImportRow[] = rows.map((r) => {
      const statusStr = String(r['状态'] ?? r['status'] ?? '').trim()
      const avatarStr = String(r['头像URL'] ?? r['avatar'] ?? r['头像'] ?? '').trim()
      return {
        cardNo: String(r['卡号'] ?? r['cardNo'] ?? '').trim(),
        phone: String(r['手机号'] ?? r['phone'] ?? '').trim() || undefined,
        name: String(r['姓名'] ?? r['name'] ?? '').trim(),
        departmentName: String(r['部门名称'] ?? r['departmentName'] ?? '').trim() || undefined,
        balance: Number(r['初始余额'] ?? r['balance'] ?? 0) || 0,
        password: String(r['密码'] ?? r['password'] ?? '').trim() || undefined,
        status: statusStr === '禁用' ? 0 : 1,
        avatar: avatarStr || undefined,
      }
    }).filter((e) => e.cardNo || e.name)

    if (employees.length === 0) {
      ElMessage.warning('未解析到有效数据,请检查文件格式')
      importLoading.value = false
      return
    }

    const res = await employeeApi.batchImport(sid.value ?? 0, employees)
    importResult.value = res
    if (res.failed === 0) {
      const parts = [`成功 ${res.success} 条`]
      if (res.created != null) parts.push(`新增 ${res.created} 条`)
      if (res.updated != null) parts.push(`更新 ${res.updated} 条`)
      ElMessage.success(`导入完成：${parts.join('，')}`)
    } else {
      ElMessage.warning(`导入完成: 成功 ${res.success} 条, 失败 ${res.failed} 条`)
    }
    await fetchList()
  } catch (e) {
    /* 拦截器提示 */
  } finally {
    importLoading.value = false
  }
}

const openImportDialog = () => {
  importResult.value = null
  importDialogVisible.value = true
}

// ===== 查看员工订单 =====
const orderDialogVisible = ref(false)
const orderLoading = ref(false)
const employeeOrders = ref<Order[]>([])
const orderEmployeeName = ref('')

const mealLabel = (m?: number) =>
  m != null ? (MEAL_TYPE as Record<number, { label: string }>)[m]?.label : ''

const openEmployeeOrders = async (row: Employee) => {
  if (!row.id) return
  orderEmployeeName.value = row.name
  orderDialogVisible.value = true
  orderLoading.value = true
  employeeOrders.value = []
  try {
    employeeOrders.value = await orderApi.listByEmployee(row.id)
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    orderLoading.value = false
  }
}

// ===== 余额预警 =====
const lowBalanceVisible = ref(false)
const lowBalanceLoading = ref(false)
const lowBalanceThreshold = ref(20)
const lowBalanceStats = ref<LowBalanceStats | null>(null)
const lowBalanceList = ref<Employee[]>([])
const lowBalancePage = ref(1)
const lowBalanceSize = ref(10)
const lowBalanceTotal = ref(0)

const openLowBalance = async () => {
  lowBalanceVisible.value = true
  lowBalancePage.value = 1
  await fetchLowBalance()
}

const fetchLowBalance = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    ElMessage.warning('请先选择食堂')
    return
  }
  lowBalanceLoading.value = true
  try {
    const [list, stats] = await Promise.all([
      employeeApi.lowBalanceList({
        storeId: sidVal,
        threshold: lowBalanceThreshold.value,
        page: lowBalancePage.value,
        size: lowBalanceSize.value,
      }),
      employeeApi.lowBalanceStats(sidVal, lowBalanceThreshold.value),
    ])
    const data = list as unknown as PageResult<Employee>
    lowBalanceList.value = data.records ?? []
    lowBalanceTotal.value = data.total ?? 0
    lowBalanceStats.value = stats
  } catch {
    /* 拦截器提示 */
  } finally {
    lowBalanceLoading.value = false
  }
}

const handleLowBalanceThresholdChange = () => {
  lowBalancePage.value = 1
  fetchLowBalance()
}

const handleLowBalancePageChange = (p: number) => {
  lowBalancePage.value = p
  fetchLowBalance()
}

const handleLowBalanceRecharge = (row: Employee) => {
  lowBalanceVisible.value = false
  openRecharge(row)
}

// ===== 导出员工 =====
const exportLoading = ref(false)

const handleExport = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    ElMessage.warning('请先选择食堂')
    return
  }
  exportLoading.value = true
  try {
    const blob = await employeeApi.export({
      storeId: sidVal,
      keyword: keyword.value || undefined,
      department: departmentId.value,
    })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
    a.download = `employees-${sidVal}-${ts}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    /* 拦截器提示 */
  } finally {
    exportLoading.value = false
  }
}

onMounted(() => {
  fetchList()
  fetchDepartments()
})

// ===== 批量导入照片 =====
interface PhotoImportItem {
  file: File
  cardNo: string
  preview: string
  status: 'pending' | 'uploading' | 'success' | 'error'
  progress: number
  error: string
  /** 该卡号当前已有头像（替换提示） */
  existingAvatar?: string
}

const photoImportVisible = ref(false)
const photoImportItems = ref<PhotoImportItem[]>([])
const photoImportLoading = ref(false)
const photoFileInput = ref<HTMLInputElement | null>(null)

/** 从文件名提取标识符(去扩展名,作为卡号/手机号/姓名的匹配 key) */
const extractCardNo = (fileName: string): string => {
  const lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
  const base = lastSlash >= 0 ? fileName.slice(lastSlash + 1) : fileName
  const dot = base.lastIndexOf('.')
  return dot >= 0 ? base.slice(0, dot).trim() : base.trim()
}

/** 打开照片导入弹窗 */
const openPhotoImport = () => {
  photoImportItems.value = []
  photoImportVisible.value = true
}

/** 触发文件选择 */
const triggerPhotoFileInput = () => {
  photoFileInput.value?.click()
}

/** 选择照片文件后处理:同卡号去重(后选覆盖先选) + 标记已有头像 */
const handlePhotoFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = '' // 清空,允许重复选择同一文件

  if (files.length === 0) return
  const imageFiles = files.filter((f) => f.type.startsWith('image/'))
  if (imageFiles.length === 0) {
    ElMessage.warning('请选择图片文件')
    return
  }

  // 同卡号去重:后选的覆盖先选的
  const seen = new Map<string, PhotoImportItem>()
  for (const file of imageFiles) {
    const cardNo = extractCardNo(file.name)
    if (!cardNo) continue
    const item: PhotoImportItem = {
      file,
      cardNo,
      preview: URL.createObjectURL(file),
      status: 'pending',
      progress: 0,
      error: '',
    }
    // 释放旧项的 objectURL 避免内存泄漏
    const existing = seen.get(cardNo)
    if (existing?.preview) {
      URL.revokeObjectURL(existing.preview)
    }
    seen.set(cardNo, item)
  }
  photoImportItems.value = Array.from(seen.values())

  // 标记已有头像的项(供 UI 显示"将替换"提示)
  if (photoImportItems.value.length > 0) {
    const cardNos = photoImportItems.value.map((it) => it.cardNo)
    try {
      // 复用当前列表 + 后端查询获取已有头像
      const existingMap = new Map<string, string>()
      // 从当前已加载的员工列表中查找(覆盖第一页常见情况)
      for (const emp of employees.value) {
        if (emp.cardNo && cardNos.includes(emp.cardNo) && emp.avatar) {
          existingMap.set(emp.cardNo, emp.avatar)
        }
      }
      // 标记
      for (const item of photoImportItems.value) {
        const existing = existingMap.get(item.cardNo)
        if (existing) {
          item.existingAvatar = existing
        }
      }
    } catch {
      /* 忽略,标记失败不影响上传 */
    }
  }

  ElMessage.info(`已选择 ${photoImportItems.value.length} 张照片`)
}

/** 批量上传照片 */
const startPhotoUpload = async () => {
  const pending = photoImportItems.value.filter((it) => it.status === 'pending')
  if (pending.length === 0) {
    ElMessage.warning('没有待上传的照片')
    return
  }
  // 如有已有头像项,二次确认
  const replaceCount = pending.filter((it) => it.existingAvatar).length
  if (replaceCount > 0) {
    try {
      await ElMessageBox.confirm(
        `检测到 ${replaceCount} 名员工已有头像,将继续上传并替换旧头像。是否继续？`,
        '替换确认',
        { type: 'warning', confirmButtonText: '继续替换', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }

  photoImportLoading.value = true
  let success = 0
  let failed = 0
  // 串行上传(避免并发过大冲击服务器),跟踪当前索引以驱动三状态头像 UI
  for (const item of pending) {
    currentUploadIdx.value = photoImportItems.value.indexOf(item)
    item.status = 'uploading'
    item.progress = 30
    try {
      // 前端压缩后再上传,避免大图触发服务器体积限制(默认 200KB/最大 800px)
      const compressed = await compressImage(item.file)
      await employeeApi.uploadAvatar(item.cardNo, compressed, sid.value || undefined)
      item.status = 'success'
      item.progress = 100
      success++
    } catch (e: unknown) {
      item.status = 'error'
      item.progress = 0
      const msg = e instanceof Error ? e.message : '上传失败'
      item.error = msg.includes('员工不存在') || msg.includes('未匹配到员工') ? '未匹配到员工' : msg
      failed++
    }
  }
  currentUploadIdx.value = -1
  photoImportLoading.value = false

  if (failed === 0) {
    ElMessage.success(`全部上传成功,共 ${success} 张`)
    photoImportVisible.value = false
  } else {
    ElMessage.warning(`上传完成: 成功 ${success} 张, 失败 ${failed} 张`)
  }
  // 刷新列表(头像已更新)
  if (success > 0) fetchList()
}

/** 清理预览 URL(关闭弹窗时) */
const onPhotoImportClose = () => {
  for (const item of photoImportItems.value) {
    if (item.preview) URL.revokeObjectURL(item.preview)
  }
  photoImportItems.value = []
  currentUploadIdx.value = -1
}

/** 当前正在上传的索引(-1 表示未开始) */
const currentUploadIdx = ref(-1)

/** 上一个已完成的项(已上传) */
const lastDoneItem = computed<PhotoImportItem | null>(() => {
  if (currentUploadIdx.value < 1) return null
  for (let i = currentUploadIdx.value - 1; i >= 0; i--) {
    const it = photoImportItems.value[i]
    if (it.status === 'success' || it.status === 'error') return it
  }
  return null
})

/** 当前正在上传的项 */
const uploadingItem = computed<PhotoImportItem | null>(() => {
  if (currentUploadIdx.value < 0 || currentUploadIdx.value >= photoImportItems.value.length) return null
  return photoImportItems.value[currentUploadIdx.value]
})

/** 下一个待上传的项(准备上传) */
const nextItem = computed<PhotoImportItem | null>(() => {
  if (currentUploadIdx.value < 0) return null
  const next = currentUploadIdx.value + 1
  if (next >= photoImportItems.value.length) return null
  return photoImportItems.value[next]
})

/** 统计 */
const photoStats = computed(() => {
  const total = photoImportItems.value.length
  const success = photoImportItems.value.filter((it) => it.status === 'success').length
  const failed = photoImportItems.value.filter((it) => it.status === 'error').length
  const pending = photoImportItems.value.filter((it) => it.status === 'pending').length
  const uploading = photoImportItems.value.filter((it) => it.status === 'uploading').length
  return { total, success, failed, pending, uploading }
})
</script>

<template>
  <Layout>
    <PageContainer title="员工管理" description="维护员工信息、部门归属与账户余额">
      <template #actions>
        <ElButton :icon="Upload" @click="openImportDialog">导入员工</ElButton>
        <ElButton v-if="sid" :icon="ImagePlus" @click="openPhotoImport">批量照片</ElButton>
        <ElButton v-if="sid" :icon="AlertTriangle" type="danger" @click="openLowBalance">余额预警</ElButton>
        <ElButton v-if="sid" :icon="FileSpreadsheet" :loading="exportLoading" @click="handleExport">导出</ElButton>
        <ElButton v-if="sid" type="warning" :icon="Wallet" @click="openBatchRecharge">批量充值</ElButton>
        <ElButton v-if="sid" type="primary" :icon="Plus" @click="openAdd">添加员工</ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElInput
          v-model="keyword"
          placeholder="搜索姓名 / 卡号"
          clearable
          style="width: 180px"
          aria-label="搜索姓名或卡号"
          @keyup.enter="handleSearch"
        />
        <ElSelect
          v-if="sid"
          v-model="departmentId"
          placeholder="全部部门"
          clearable
          style="width: 160px"
          aria-label="筛选部门"
          @change="handleSearch"
        >
          <ElOption v-for="d in departments" :key="d.id" :label="d.name" :value="d.id!" />
        </ElSelect>
      </SearchBar>

      <div class="card overflow-hidden">
        <ElTable
          v-loading="loading"
          :data="employees"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
          aria-label="员工列表"
        >
          <ElTableColumn label="头像" width="80" align="center" header-align="center">
            <template #default="{ row }">
              <ElImage
                v-if="row.avatar"
                :src="row.avatar"
                :preview-src-list="[row.avatar]"
                preview-teleported
                fit="cover"
                class="block h-9 w-9 rounded-full"
              />
              <div
                v-else
                class="mx-auto flex h-9 w-9 items-center justify-center rounded-full bg-bg-tertiary text-text-muted"
              >
                <UserCircle2 class="h-5 w-5" />
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="cardNo" label="卡号" min-width="140" align="left" header-align="left" />
          <ElTableColumn prop="phone" label="手机号" min-width="130" align="left" header-align="left">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.phone || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="name" label="姓名" min-width="120" align="left" header-align="left" />
          <ElTableColumn label="部门" min-width="120" align="left" header-align="left">
            <template #default="{ row }">
              {{ row.departmentName || deptName(row.departmentId) || '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="余额" width="120" align="right" header-align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.balance ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center" header-align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="COMMON_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="430" fixed="right" align="left" header-align="left" :show-overflow-tooltip="false">
            <template #default="{ row }">
              <ElButton size="small" :icon="Pencil" @click="openEdit(row as Employee)">编辑</ElButton>
              <ElButton size="small" :icon="ClipboardList" @click="openEmployeeOrders(row as Employee)">订单</ElButton>
              <ElButton
                size="small"
                :type="row.status === 1 ? 'info' : 'success'"
                :icon="row.status === 1 ? PowerOff : Power"
                @click="handleToggleStatus(row as Employee)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </ElButton>
              <ElButton size="small" type="warning" :icon="Wallet" @click="openRecharge(row as Employee)">
                充值
              </ElButton>
              <ElButton size="small" type="danger" plain :icon="Trash2" class="delete-btn" @click="handleDelete(row.id)">删除</ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无员工数据" />
          </template>
        </ElTable>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <span class="text-xs text-text-muted">共 {{ total }} 条</span>
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="handlePageChange"
            @size-change="(s: number) => { size = s; page = 1; fetchList() }"
          />
        </div>
      </div>

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑员工' : '新增员工'"
        width="500px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
        @opened="onDialogOpened"
        @closed="onDialogClosed"
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="80px">
          <ElFormItem label="头像">
            <div class="flex items-center gap-4">
              <ImageUploader
                v-model="form.avatar"
                label="头像"
                :preview-size="64"
                hint="自动压缩到 200KB 以内,支持 JPG/PNG/WebP"
              />
            </div>
          </ElFormItem>
          <ElFormItem label="卡号" prop="cardNo">
            <ElInput
              ref="cardNoInput"
              v-model="form.cardNo"
              placeholder="将光标置于此处后刷卡,自动填入卡号"
              aria-required="true"
              clearable
            >
              <template #suffix>
                <span
                  class="card-hint-icon"
                  title="读卡器需要安装读卡助手。请点击右上角下载按钮获取。通用模拟键盘/HID 读卡器无需读卡助手。"
                >
                  <Info :size="16" />
                </span>
              </template>
            </ElInput>
          </ElFormItem>
          <ElFormItem label="手机号" prop="phone">
            <ElInput v-model="form.phone" placeholder="用于 H5/小程序登录(同店内唯一)" maxlength="11" aria-required="true" />
          </ElFormItem>
          <ElFormItem label="姓名" prop="name">
            <ElInput v-model="form.name" placeholder="请输入员工姓名" aria-required="true" />
          </ElFormItem>
          <ElFormItem label="部门">
            <ElSelect v-model="form.departmentId" placeholder="请选择部门" clearable class="w-full">
              <ElOption v-for="d in departments" :key="d.id" :label="d.name" :value="d.id!" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="初始余额">
            <ElInputNumber v-model="form.balance" :min="0" :precision="2" class="w-full" />
          </ElFormItem>
          <ElFormItem label="密码">
            <ElInput
              v-model="form.password"
              :placeholder="isEdit ? '留空则不修改密码' : '留空默认为 12345678'"
              type="password"
              show-password
            />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>

      <!-- 充值弹窗 -->
      <ElDialog
        v-model="rechargeVisible"
        title="员工充值"
        width="400px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div v-if="rechargeEmployee" class="mb-4 rounded-lg bg-bg-secondary px-4 py-3">
          <div class="text-sm text-text-secondary">
            充值对象：<span class="font-medium text-text">{{ rechargeEmployee.name }}</span>
            （{{ rechargeEmployee.cardNo }}）
          </div>
          <div class="mt-1 text-sm text-text-secondary">
            当前余额：<span class="font-medium tabular-nums text-text">¥{{ rechargeEmployee.balance ?? 0 }}</span>
          </div>
        </div>
        <ElForm label-width="80px">
          <ElFormItem label="充值金额">
            <ElInputNumber
              v-model="rechargeAmount"
              :min="0.01"
              :precision="2"
              :step="50"
              class="w-full"
              placeholder="请输入充值金额"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="rechargeVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="rechargeLoading" @click="confirmRecharge">
            确认充值
          </ElButton>
        </template>
      </ElDialog>

      <!-- 批量充值弹窗 -->
      <ElDialog
        v-model="batchRechargeVisible"
        title="批量充值"
        width="420px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div class="mb-4 rounded-lg bg-bg-secondary px-4 py-3 text-sm text-text-secondary">
          将给本食堂所有在职员工充值指定金额。
        </div>
        <ElForm label-width="80px">
          <ElFormItem label="充值金额">
            <ElInputNumber
              v-model="batchRechargeAmount"
              :min="0.01"
              :precision="2"
              :step="50"
              class="w-full"
              placeholder="请输入充值金额"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="batchRechargeVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="batchRechargeLoading" @click="confirmBatchRecharge">
            确认充值
          </ElButton>
        </template>
      </ElDialog>

      <!-- 导入弹窗 -->
      <ElDialog
        v-model="importDialogVisible"
        title="批量导入员工"
        width="640px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div class="space-y-4">
          <div class="rounded-lg bg-bg-secondary px-4 py-3 text-sm text-text-secondary">
            <div class="mb-1 font-medium text-text">导入说明</div>
            <ul class="list-disc space-y-1 pl-5 text-xs">
              <li>请先下载模板，按模板格式填写员工信息。</li>
              <li>表头：卡号 / 手机号 / 姓名 / 部门名称 / 初始余额 / 密码 / 状态（启用/禁用）/ 头像URL。</li>
              <li>部门名称需与门店已有部门一致，否则该字段会被忽略。</li>
              <li><b>重复导入可用于批量更新</b>：按姓名匹配同门店已有员工，自动更新卡号/手机号/密码/部门（有变化才更新，无变化则跳过）。</li>
              <li>卡号在同门店内必须唯一，重复行会被跳过并记录。</li>
              <li>手机号同门店内唯一,留空则该员工无法用手机号登录(H5/小程序)。</li>
              <li>密码留空默认为 <code class="rounded bg-bg-tertiary px-1">12345678</code>。</li>
              <li>头像URL：可填写图片链接（http/https）或 dataURL；留空则无头像。Excel 无法嵌入本地图片，批量上传本地图片请用单条编辑里的头像上传功能。</li>
            </ul>
          </div>
          <div class="flex items-center gap-3">
            <ElButton :icon="Download" @click="handleDownloadTemplate">下载导入模板</ElButton>
            <ElUpload
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleImportFile"
              accept=".xlsx,.xls"
            >
              <ElButton type="primary" :icon="Upload" :loading="importLoading">
                选择文件并导入
              </ElButton>
            </ElUpload>
          </div>

          <div v-if="importResult" class="rounded-lg border border-border p-4">
            <div class="mb-2 flex items-center gap-4 text-sm">
              <span class="text-success">成功：{{ importResult.success }} 条</span>
              <span v-if="importResult.created != null" class="text-primary">新增：{{ importResult.created }} 条</span>
              <span v-if="importResult.updated != null" class="text-warning">更新：{{ importResult.updated }} 条</span>
              <span class="text-danger">失败：{{ importResult.failed }} 条</span>
            </div>
            <ElTable
              v-if="importResult.errors.length > 0"
              :data="importResult.errors"
              size="small"
              max-height="240"
              border
            >
              <ElTableColumn prop="row" label="行号" width="70" align="center" />
              <ElTableColumn prop="cardNo" label="卡号" width="140" align="left" />
              <ElTableColumn prop="phone" label="手机号" width="130" align="left" />
              <ElTableColumn prop="name" label="姓名" width="120" align="left" />
              <ElTableColumn prop="reason" label="失败原因" min-width="180" align="left" />
            </ElTable>
            <div v-else class="text-sm text-text-muted">全部导入成功，无错误信息。</div>
          </div>
        </div>
        <template #footer>
          <ElButton @click="importDialogVisible = false">关闭</ElButton>
        </template>
      </ElDialog>

      <!-- 员工订单弹窗 -->
      <ElDialog
        v-model="orderDialogVisible"
        :title="`员工订单 - ${orderEmployeeName}`"
        width="720px"
        append-to-body
        destroy-on-close
      >
        <ElTable
          v-loading="orderLoading"
          :data="employeeOrders"
          style="width: 100%"
          :show-overflow-tooltip="true"
          max-height="420"
          aria-label="员工订单列表"
        >
          <ElTableColumn prop="orderNo" label="订单号" min-width="160" />
          <ElTableColumn prop="date" label="日期" width="120" />
          <ElTableColumn label="餐次" width="90" align="center">
            <template #default="{ row }">{{ mealLabel(row.mealType) }}</template>
          </ElTableColumn>
          <ElTableColumn label="金额" width="110" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ row.totalAmount }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status" :map="ORDER_STATUS" />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无订单数据" />
          </template>
        </ElTable>
        <template #footer>
          <ElButton @click="orderDialogVisible = false">关闭</ElButton>
        </template>
      </ElDialog>

      <!-- 余额预警弹窗 -->
      <ElDialog
        v-model="lowBalanceVisible"
        title="余额预警名单"
        width="780px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div class="space-y-4">
          <!-- 阈值与统计 -->
          <div class="rounded-lg border border-border bg-bg-secondary px-4 py-3">
            <div class="mb-3 flex flex-wrap items-center gap-3">
              <span class="text-sm text-text-secondary">预警阈值：</span>
              <ElInputNumber
                v-model="lowBalanceThreshold"
                :min="0"
                :precision="2"
                :step="10"
                size="small"
                style="width: 140px"
                aria-label="预警阈值"
              />
              <span class="text-xs text-text-muted">元（余额低于此值的员工将列入预警）</span>
              <ElButton size="small" type="primary" @click="handleLowBalanceThresholdChange">
                查询
              </ElButton>
            </div>
            <div class="grid grid-cols-3 gap-3" v-if="lowBalanceStats">
              <StatCard
                title="预警人数"
                :value="lowBalanceStats.count"
                :icon="AlertTriangle"
                color="danger"
              />
              <StatCard
                title="总余额"
                :value="`¥${formatMoney(lowBalanceStats.totalBalance)}`"
                :icon="Wallet"
                color="warning"
              />
              <StatCard
                title="平均余额"
                :value="`¥${lowBalanceStats.avgBalance}`"
                :icon="Wallet"
                color="accent"
              />
            </div>
          </div>

          <!-- 预警名单表格 -->
          <ElTable
            v-loading="lowBalanceLoading"
            :data="lowBalanceList"
            style="width: 100%"
            :show-overflow-tooltip="true"
            max-height="360"
            aria-label="余额预警名单"
          >
            <ElTableColumn prop="name" label="姓名" min-width="120" align="left" />
            <ElTableColumn prop="cardNo" label="卡号" min-width="140" align="left" />
            <ElTableColumn prop="phone" label="手机号" min-width="130" align="left">
              <template #default="{ row }">
                <span class="text-text-secondary">{{ row.phone || '—' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="余额" width="140" align="right" header-align="right">
              <template #default="{ row }">
                <span class="font-medium tabular-nums text-danger">¥{{ row.balance ?? 0 }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="140" align="left" fixed="right">
              <template #default="{ row }">
                <ElButton size="small" type="warning" :icon="Wallet" @click="handleLowBalanceRecharge(row as Employee)">
                  一键充值
                </ElButton>
              </template>
            </ElTableColumn>
            <template #empty>
              <EmptyState description="暂无预警员工,所有员工余额充足" />
            </template>
          </ElTable>

          <div v-if="lowBalanceTotal > lowBalanceSize" class="flex items-center justify-end">
            <ElPagination
              v-model:current-page="lowBalancePage"
              v-model:page-size="lowBalanceSize"
              :page-sizes="[10, 20, 50]"
              :total="lowBalanceTotal"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="handleLowBalancePageChange"
              @size-change="(s: number) => { lowBalanceSize = s; lowBalancePage = 1; fetchLowBalance() }"
            />
          </div>
        </div>
        <template #footer>
          <ElButton @click="lowBalanceVisible = false">关闭</ElButton>
        </template>
      </ElDialog>

      <!-- 批量导入照片弹窗 -->
      <ElDialog
        v-model="photoImportVisible"
        title="批量导入员工照片"
        width="520px"
        :close-on-click-modal="false"
        @close="onPhotoImportClose"
      >
        <input
          ref="photoFileInput"
          type="file"
          accept="image/*"
          multiple
          style="display: none"
          @change="handlePhotoFileChange"
        />

        <!-- 说明 -->
        <div class="mb-5 text-sm text-gray-500 leading-relaxed">
          文件名(去扩展名)作为匹配标识符自动识别员工:支持<b>卡号</b> / <b>手机号</b> / <b>姓名</b> 自动适配(优先卡号,再手机号,后姓名)。同名多次选择,后选的覆盖先选的。
        </div>

        <!-- 空状态 -->
        <div v-if="photoImportItems.length === 0" class="py-10 text-center">
          <div class="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-full bg-gray-100">
            <ImagePlus class="h-9 w-9 text-gray-400" />
          </div>
          <p class="text-gray-400 mb-4">点击下方按钮选择员工照片(支持多选)</p>
          <ElButton type="primary" :icon="ImagePlus" round @click="triggerPhotoFileInput">选择照片</ElButton>
        </div>

        <!-- 已选择:圆圈头像 + 数量(未上传时) -->
        <div v-else-if="!photoImportLoading && currentUploadIdx < 0 && photoStats.pending > 0" class="py-4 text-center">
          <!-- 圆圈头像(显示第一张) -->
          <div class="relative mx-auto mb-4 h-28 w-28">
            <img
              :src="photoImportItems[0].preview"
              :alt="photoImportItems[0].cardNo"
              class="h-28 w-28 rounded-full object-cover ring-4 ring-primary/30"
            />
            <div class="absolute -bottom-1 -right-1 flex h-8 w-8 items-center justify-center rounded-full bg-primary text-white text-sm font-bold shadow-lg">
              {{ photoStats.total }}
            </div>
          </div>
          <p class="text-base font-medium text-gray-700">已选择 {{ photoStats.total }} 张照片</p>
          <p class="mt-1 text-xs text-gray-400">点击"确认上传"开始批量导入</p>
          <div class="mt-4 flex justify-center gap-3">
            <ElButton :icon="ImagePlus" @click="triggerPhotoFileInput">重新选择</ElButton>
          </div>
        </div>

        <!-- 上传中:三状态头像(已上传 / 正在上传 / 下一个) -->
        <div v-else-if="photoImportLoading || currentUploadIdx >= 0" class="py-6">
          <!-- 三状态头像 -->
          <div class="flex items-end justify-center gap-8">
            <!-- 1. 已上传(上一个完成的) -->
            <div class="flex flex-col items-center gap-2">
              <div class="relative h-20 w-20">
                <img
                  v-if="lastDoneItem"
                  :src="lastDoneItem.preview"
                  :alt="lastDoneItem.cardNo"
                  class="h-20 w-20 rounded-full object-cover ring-4 ring-green-400"
                />
                <div v-else class="h-20 w-20 rounded-full bg-gray-100 flex items-center justify-center">
                  <ImagePlus class="h-7 w-7 text-gray-300" />
                </div>
                <!-- 完成勾 -->
                <div
                  v-if="lastDoneItem"
                  class="absolute -bottom-1 -right-1 flex h-7 w-7 items-center justify-center rounded-full shadow-lg"
                  :class="lastDoneItem.status === 'success' ? 'bg-green-500' : 'bg-red-500'"
                >
                  <svg v-if="lastDoneItem.status === 'success'" class="h-4 w-4 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M5 13l4 4L19 7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <span v-else class="text-white text-xs font-bold">!</span>
                </div>
              </div>
              <span class="text-xs text-gray-500">已上传</span>
            </div>

            <!-- 2. 正在上传(绿色圈圈代表进度) -->
            <div class="flex flex-col items-center gap-2">
              <div class="relative h-28 w-28">
                <!-- SVG 进度圈 -->
                <svg class="absolute inset-0 -rotate-90" width="112" height="112" viewBox="0 0 112 112">
                  <circle cx="56" cy="56" r="52" stroke="#e5e7eb" stroke-width="6" fill="none" />
                  <circle
                    cx="56" cy="56" r="52" stroke="#22c55e" stroke-width="6" fill="none"
                    stroke-linecap="round"
                    :stroke-dasharray="2 * Math.PI * 52"
                    :stroke-dashoffset="2 * Math.PI * 52 * (1 - (uploadingItem?.progress || 0) / 100)"
                    style="transition: stroke-dashoffset 0.3s ease;"
                  />
                </svg>
                <!-- 头像 -->
                <img
                  v-if="uploadingItem"
                  :src="uploadingItem.preview"
                  :alt="uploadingItem.cardNo"
                  class="absolute inset-2 h-24 w-24 rounded-full object-cover"
                />
                <div v-else class="absolute inset-2 h-24 w-24 rounded-full bg-gray-100 flex items-center justify-center">
                  <ImagePlus class="h-8 w-8 text-gray-300" />
                </div>
              </div>
              <span class="text-xs font-medium text-green-600">正在上传</span>
              <span v-if="uploadingItem" class="text-xs text-gray-400 truncate max-w-[100px]">{{ uploadingItem.cardNo }}</span>
            </div>

            <!-- 3. 下一个(准备上传) -->
            <div class="flex flex-col items-center gap-2">
              <div class="relative h-20 w-20">
                <img
                  v-if="nextItem"
                  :src="nextItem.preview"
                  :alt="nextItem.cardNo"
                  class="h-20 w-20 rounded-full object-cover opacity-50 ring-4 ring-gray-200"
                />
                <div v-else class="h-20 w-20 rounded-full bg-gray-50 border-2 border-dashed border-gray-200 flex items-center justify-center">
                  <span class="text-gray-300 text-2xl">—</span>
                </div>
              </div>
              <span class="text-xs text-gray-400">下一个</span>
            </div>
          </div>

          <!-- 进度数字 -->
          <div class="mt-6 text-center">
            <div class="text-2xl font-bold text-gray-800">
              {{ photoStats.success + photoStats.failed }} <span class="text-base font-normal text-gray-400">/ {{ photoStats.total }}</span>
            </div>
            <div class="mt-1 flex justify-center gap-4 text-xs">
              <span class="text-green-600">成功 {{ photoStats.success }}</span>
              <span v-if="photoStats.failed > 0" class="text-red-500">失败 {{ photoStats.failed }}</span>
            </div>
          </div>
        </div>

        <!-- 上传完成(有失败项时显示) -->
        <div v-else-if="!photoImportLoading && currentUploadIdx < 0 && photoStats.pending === 0 && photoStats.failed > 0" class="py-4 text-center">
          <div class="mx-auto mb-3 flex h-16 w-16 items-center justify-center rounded-full bg-amber-100">
            <AlertTriangle class="h-8 w-8 text-amber-500" />
          </div>
          <p class="text-base font-medium text-gray-700">上传完成</p>
          <p class="mt-1 text-sm text-gray-500">
            成功 {{ photoStats.success }} 张,失败 {{ photoStats.failed }} 张
          </p>
          <div class="mt-4 max-h-32 overflow-y-auto rounded-lg bg-red-50 p-3 text-left">
            <div v-for="(it, i) in photoImportItems.filter(x => x.status === 'error')" :key="i" class="text-xs text-red-600">
              {{ it.cardNo }}: {{ it.error }}
            </div>
          </div>
        </div>

        <template #footer>
          <ElButton @click="photoImportVisible = false">取消</ElButton>
          <ElButton
            v-if="photoStats.pending > 0"
            type="primary"
            :loading="photoImportLoading"
            :disabled="photoImportLoading"
            @click="startPhotoUpload"
          >
            确认上传 ({{ photoStats.pending }})
          </ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>

<style scoped>
/* 卡号输入框末尾的读卡提示图标 */
.card-hint-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  color: var(--el-text-color-secondary);
  cursor: help;
}
</style>
