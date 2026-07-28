<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Eye, Trash2, CheckCircle2, XCircle, Flag, Users } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { groupOrderApi, employeeApi, dishApi } from '@/api'
import type { GroupOrder, GroupOrderItem, GroupOrderDetail, Employee, Dish, PageResult } from '@/api/types'
import { MEAL_TYPE } from '@/constants/dict'
import { formatMoney } from '@/utils/money'

const authStore = useAuthStore()
const sid = computed(() => authStore.storeId || null)

// 团体订单状态字典
const GROUP_ORDER_STATUS: Record<number, { label: string; type: 'warning' | 'primary' | 'info' | 'success' }> = {
  1: { label: '待确认', type: 'warning' },
  2: { label: '已确认', type: 'primary' },
  3: { label: '已取消', type: 'info' },
  4: { label: '已完成', type: 'success' },
}

const mealTypeLabel = (t?: number) =>
  t ? (MEAL_TYPE as Record<number, { label: string }>)[t]?.label ?? '—' : '—'

// 筛选
const statusFilter = ref<number | undefined>(undefined)
const dateRange = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const list = ref<GroupOrder[]>([])

const fetchList = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await groupOrderApi.list({
      storeId: sidVal,
      page: page.value,
      size: size.value,
      status: statusFilter.value,
      startDate: dateRange.value?.[0] || undefined,
      endDate: dateRange.value?.[1] || undefined,
    })
    const data = res as unknown as PageResult<GroupOrder>
    list.value = data.records ?? []
    total.value = data.total ?? 0
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

// 员工列表(用于组织人选择)
const employees = ref<Employee[]>([])
const loadEmployees = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    employees.value = []
    return
  }
  try {
    const res = await employeeApi.list({ storeId: sidVal, size: 999 })
    employees.value = res.records ?? []
  } catch {
    employees.value = []
  }
}

// 菜品列表(用于明细选择)
const dishes = ref<Dish[]>([])
const loadDishes = async () => {
  const sidVal = sid.value
  if (!sidVal) {
    dishes.value = []
    return
  }
  try {
    const res = await dishApi.list({ storeId: sidVal, size: 999 })
    dishes.value = res.records ?? []
  } catch {
    dishes.value = []
  }
}

// 创建弹窗
const createDialogVisible = ref(false)
const createLoading = ref(false)
const formRef = ref<FormInstance>()
const defaultForm = (): GroupOrder => ({
  storeId: sid.value ?? 0,
  title: '',
  organizerId: null,
  headcount: 1,
  mealDate: new Date().toISOString().slice(0, 10),
  mealType: 2,
  location: '',
  remark: '',
})
const form = ref<GroupOrder>(defaultForm())
const items = ref<GroupOrderItem[]>([])

const rules: FormRules = {
  title: [{ required: true, message: '请输入订单标题', trigger: 'blur' }],
  mealDate: [{ required: true, message: '请选择用餐日期', trigger: 'change' }],
  mealType: [{ required: true, message: '请选择餐次', trigger: 'change' }],
  headcount: [{ required: true, message: '请输入用餐人数', trigger: 'blur' }],
}

const defaultItem = (): GroupOrderItem => ({
  dishId: 0,
  quantity: 1,
})

const addItem = () => {
  items.value.push(defaultItem())
}

const removeItem = (index: number) => {
  items.value.splice(index, 1)
}

// 当菜品选择变化时,同步菜品名称与单价
const onDishChange = (item: GroupOrderItem) => {
  const dish = dishes.value.find((d) => d.id === item.dishId)
  if (dish) {
    item.dishName = dish.name
    item.price = dish.price
  }
}

const totalAmount = computed(() =>
  items.value.reduce((sum, it) => sum + (Number(it.price) || 0) * (Number(it.quantity) || 0), 0)
)

const openCreate = () => {
  form.value = defaultForm()
  items.value = [defaultItem()]
  createDialogVisible.value = true
  loadEmployees()
  loadDishes()
}

const handleCreate = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const validItems = items.value.filter((it) => it.dishId)
  if (validItems.length === 0) {
    ElMessage.warning('请至少添加一条菜品明细')
    return
  }
  for (const it of validItems) {
    if (!it.quantity || it.quantity <= 0) {
      ElMessage.warning('份数必须大于 0')
      return
    }
  }
  createLoading.value = true
  try {
    await groupOrderApi.create({
      groupOrder: { ...form.value, headcount: Number(form.value.headcount) },
      items: validItems.map((it) => ({
        ...it,
        dishId: Number(it.dishId),
        quantity: Number(it.quantity),
      })),
    })
    ElMessage.success('团体订单创建成功')
    createDialogVisible.value = false
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    createLoading.value = false
  }
}

// 详情抽屉
const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<GroupOrderDetail | null>(null)

const openDetail = async (row: GroupOrder) => {
  if (!row.id) return
  detailDrawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await groupOrderApi.detail(row.id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// 状态操作
const handleConfirm = async (row: GroupOrder) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认团体订单 ${row.orderNo} 吗?`, '确认订单', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await groupOrderApi.confirm(row.id)
    ElMessage.success('已确认')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleComplete = async (row: GroupOrder) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认将团体订单 ${row.orderNo} 标记为已完成吗?`, '完成确认', {
      type: 'warning',
      confirmButtonText: '确认完成',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await groupOrderApi.complete(row.id)
    ElMessage.success('已完成')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleCancel = async (row: GroupOrder) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认取消团体订单 ${row.orderNo} 吗?`, '取消确认', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  try {
    await groupOrderApi.cancel(row.id)
    ElMessage.success('已取消')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleDelete = async (row: GroupOrder) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认删除团体订单 ${row.orderNo} 吗?仅待确认状态可删除。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await groupOrderApi.delete(row.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  statusFilter.value = undefined
  dateRange.value = null
  page.value = 1
  fetchList()
}

const formatTime = (t?: string) => {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

watch(sid, () => {
  page.value = 1
  fetchList()
})

onMounted(fetchList)
</script>

<template>
  <Layout>
    <PageContainer title="团体订餐管理" description="管理会议餐、团体订餐,支持菜品明细、状态流转与确认">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">新增团体订单</ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElSelect
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <ElOption label="待确认" :value="1" />
          <ElOption label="已确认" :value="2" />
          <ElOption label="已取消" :value="3" />
          <ElOption label="已完成" :value="4" />
        </ElSelect>
        <ElDatePicker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
          @change="handleSearch"
        />
      </SearchBar>

      <div class="card overflow-hidden">
        <ElTable
          v-loading="loading"
          :data="list"
          style="width: 100%"
          :show-overflow-tooltip="true"
          row-key="id"
        >
          <ElTableColumn label="订单号" min-width="180">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <Users class="h-4 w-4 text-primary" />
                <span class="font-mono font-medium text-text">{{ row.orderNo }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="标题" min-width="180">
            <template #default="{ row }">
              <span class="text-text">{{ row.title }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="用餐日期" width="120" align="center">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.mealDate || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="餐次" width="90" align="center">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ mealTypeLabel(row.mealType) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="人数" width="80" align="center">
            <template #default="{ row }">
              <span class="tabular-nums text-text">{{ row.headcount }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="总金额" width="120" align="right">
            <template #default="{ row }">
              <span class="font-medium tabular-nums text-text">¥{{ formatMoney(row.totalAmount) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status ?? 1" :map="GROUP_ORDER_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="320" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" :icon="Eye" @click="openDetail(row as GroupOrder)">详情</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="primary"
                :icon="CheckCircle2"
                @click="handleConfirm(row as GroupOrder)"
              >确认</ElButton>
              <ElButton
                v-if="row.status === 2"
                size="small"
                type="success"
                :icon="Flag"
                @click="handleComplete(row as GroupOrder)"
              >完成</ElButton>
              <ElButton
                v-if="row.status === 1 || row.status === 2"
                size="small"
                type="warning"
                :icon="XCircle"
                @click="handleCancel(row as GroupOrder)"
              >取消</ElButton>
              <ElButton
                v-if="row.status === 1"
                size="small"
                type="danger"
                :icon="Trash2"
                @click="handleDelete(row as GroupOrder)"
              />
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无团体订单数据" />
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
            @current-change="fetchList"
            @size-change="(s: number) => { size = s; page = 1; fetchList() }"
          />
        </div>
      </div>

      <!-- 新增团体订单弹窗 -->
      <ElDialog
        v-model="createDialogVisible"
        title="新增团体订单"
        width="900px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
          <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <ElFormItem label="订单标题" prop="title">
              <ElInput v-model="form.title" placeholder="如:3楼会议室会议餐" maxlength="200" />
            </ElFormItem>
            <ElFormItem label="组织人" prop="organizerId">
              <ElSelect v-model="form.organizerId" placeholder="选择组织人(选填)" filterable clearable class="w-full">
                <ElOption
                  v-for="e in employees"
                  :key="e.id"
                  :label="`${e.name}(${e.cardNo})`"
                  :value="e.id as number"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="用餐日期" prop="mealDate">
              <ElDatePicker
                v-model="form.mealDate"
                type="date"
                placeholder="请选择日期"
                value-format="YYYY-MM-DD"
                class="w-full"
              />
            </ElFormItem>
            <ElFormItem label="餐次" prop="mealType">
              <ElSelect v-model="form.mealType" placeholder="请选择餐次" class="w-full">
                <ElOption label="早餐" :value="1" />
                <ElOption label="午餐" :value="2" />
                <ElOption label="晚餐" :value="3" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="用餐人数" prop="headcount">
              <ElInputNumber v-model="form.headcount" :min="1" :step="1" class="w-full" />
            </ElFormItem>
            <ElFormItem label="用餐地点">
              <ElInput v-model="form.location" placeholder="如:3楼会议室" maxlength="200" />
            </ElFormItem>
          </div>
          <ElFormItem label="备注">
            <ElInput v-model="form.remark" type="textarea" :rows="2" placeholder="特殊要求(选填)" maxlength="500" />
          </ElFormItem>

          <!-- 菜品明细表格 -->
          <div class="mb-2 flex items-center justify-between">
            <span class="text-sm font-medium text-text">菜品明细</span>
            <ElButton size="small" type="primary" :icon="Plus" @click="addItem">添加行</ElButton>
          </div>
          <ElTable :data="items" border style="width: 100%" size="small">
            <ElTableColumn label="序号" type="index" width="55" align="center" />
            <ElTableColumn label="菜品" min-width="200">
              <template #default="{ row }">
                <ElSelect
                  v-model="row.dishId"
                  placeholder="请选择菜品"
                  filterable
                  size="small"
                  class="w-full"
                  @change="onDishChange(row as GroupOrderItem)"
                >
                  <ElOption
                    v-for="d in dishes"
                    :key="d.id"
                    :label="`${d.name} (¥${d.price})`"
                    :value="d.id as number"
                  />
                </ElSelect>
              </template>
            </ElTableColumn>
            <ElTableColumn label="单价(¥)" width="110" align="right">
              <template #default="{ row }">
                <span class="tabular-nums">{{ row.price ?? '—' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="份数" width="120">
              <template #default="{ row }">
                <ElInputNumber v-model="row.quantity" :min="1" :step="1" :controls="false" size="small" class="w-full" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="小计(¥)" width="110" align="right">
              <template #default="{ row }">
                <span class="tabular-nums">{{ ((Number(row.quantity) || 0) * (Number(row.price) || 0)).toFixed(2) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="80" align="center">
              <template #default="{ $index }">
                <ElButton size="small" type="danger" :icon="Trash2" link @click="removeItem($index)" />
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-end">
            <span class="text-sm text-text-muted">合计:</span>
            <span class="ml-2 text-base font-bold tabular-nums text-primary">¥{{ totalAmount.toFixed(2) }}</span>
          </div>
        </ElForm>
        <template #footer>
          <ElButton @click="createDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="createLoading" @click="handleCreate">提交</ElButton>
        </template>
      </ElDialog>

      <!-- 详情抽屉 -->
      <ElDrawer
        v-model="detailDrawerVisible"
        title="团体订单详情"
        direction="rtl"
        size="600px"
      >
        <div v-loading="detailLoading">
          <template v-if="detail">
            <div class="mb-6 rounded-lg border border-border bg-bg-secondary p-4">
              <div class="mb-3 flex items-center justify-between">
                <span class="font-mono text-base font-bold text-text">{{ detail.groupOrder.orderNo }}</span>
                <StatusTag :value="detail.groupOrder.status ?? 1" :map="GROUP_ORDER_STATUS" />
              </div>
              <div class="mb-2 text-base font-medium text-text">{{ detail.groupOrder.title }}</div>
              <div class="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span class="text-text-muted">组织人:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.organizerName || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">用餐日期:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.mealDate || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">餐次:</span>
                  <span class="ml-2 text-text">{{ mealTypeLabel(detail.groupOrder.mealType) }}</span>
                </div>
                <div>
                  <span class="text-text-muted">用餐人数:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.headcount }}</span>
                </div>
                <div class="col-span-2">
                  <span class="text-text-muted">用餐地点:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.location || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">操作人:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.operatorName || '—' }}</span>
                </div>
                <div>
                  <span class="text-text-muted">创建时间:</span>
                  <span class="ml-2 text-text">{{ formatTime(detail.groupOrder.createdAt) }}</span>
                </div>
                <div class="col-span-2">
                  <span class="text-text-muted">备注:</span>
                  <span class="ml-2 text-text">{{ detail.groupOrder.remark || '—' }}</span>
                </div>
              </div>
            </div>

            <div class="mb-2 text-sm font-medium text-text">菜品明细</div>
            <ElTable :data="detail.items" border style="width: 100%" size="small">
              <ElTableColumn label="序号" type="index" width="55" align="center" />
              <ElTableColumn prop="dishName" label="菜品名称" min-width="160" />
              <ElTableColumn label="单价" width="100" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums">¥{{ formatMoney(row.price) }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="份数" width="80" align="center">
                <template #default="{ row }">
                  <span class="tabular-nums">{{ row.quantity }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="小计" width="110" align="right">
                <template #default="{ row }">
                  <span class="tabular-nums">¥{{ formatMoney(row.amount) }}</span>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="mt-3 flex justify-end">
              <span class="text-sm text-text-muted">合计:</span>
              <span class="ml-2 text-base font-bold tabular-nums text-primary">¥{{ formatMoney(detail.groupOrder.totalAmount) }}</span>
            </div>
          </template>
          <EmptyState v-else description="暂无详情数据" />
        </div>
      </ElDrawer>
    </PageContainer>
  </Layout>
</template>
