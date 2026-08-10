<script setup lang="ts">
import { ref, computed } from 'vue'
import { onMounted } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { rechargeApi, employeeApi } from '@/api'
import type { Employee, RechargeRecord } from '@/api'
import {
  ElTable,
  ElTableColumn,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElSelect,
  ElOption,
  ElDatePicker,
  ElPagination,
  ElMessage,
} from 'element-plus'
import { Plus, Wallet, CalendarDays, Coins } from 'lucide-vue-next'
import { todayStr, monthStr } from '@/utils/date'
import { money } from '@/utils/money'
import { normalizeList } from '@/utils/list'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)

const allRecords = ref<RechargeRecord[]>([])
const employees = ref<Employee[]>([])
const loading = ref(false)

const empMap = computed<Record<number, Employee>>(() => {
  const m: Record<number, Employee> = {}
  employees.value.forEach((e) => {
    if (e.id != null) m[e.id] = e
  })
  return m
})

const empName = (id?: number) => {
  if (!id) return '—'
  return empMap.value[id]?.name || '—'
}

const fetchRecords = async () => {
  const sid = storeId.value
  if (!sid) {
    allRecords.value = []
    return
  }
  loading.value = true
  try {
    const raw = await rechargeApi.list({ storeId: sid })
    allRecords.value = normalizeList<RechargeRecord>(raw)
  } catch {
    /* 拦截器提示 */
  } finally {
    loading.value = false
  }
}

const fetchEmployees = async () => {
  const sid = storeId.value
  if (!sid) {
    employees.value = []
    return
  }
  try {
    const raw = await employeeApi.list({ storeId: sid })
    employees.value = normalizeList<Employee>(raw)
  } catch {
    /* 拦截器提示 */
  }
}

/* 筛选 */
const employeeFilter = ref<number | undefined>(undefined)
const dateRange = ref<[string, string] | null>(null)

const filteredRecords = computed(() => {
  let arr = allRecords.value
  if (employeeFilter.value) {
    arr = arr.filter((r) => r.employeeId === employeeFilter.value)
  }
  if (dateRange.value && dateRange.value.length === 2) {
    const [s, e] = dateRange.value
    arr = arr.filter((r) => {
      const d = (r.createdAt || '').slice(0, 10)
      return d >= s && d <= e
    })
  }
  return [...arr].sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
})

/* 分页 */
const page = ref(1)
const size = ref(10)
const total = computed(() => filteredRecords.value.length)
const pagedList = computed(() => {
  const start = (page.value - 1) * size.value
  return filteredRecords.value.slice(start, start + size.value)
})

const handleSearch = () => {
  page.value = 1
}
const handleReset = () => {
  employeeFilter.value = undefined
  dateRange.value = null
  page.value = 1
}

/* 统计(基于全部记录) */
const sumAmount = (arr: RechargeRecord[]) =>
  arr.reduce((s, r) => s + Number(r.amount || 0), 0)

const todayTotal = computed(() =>
  sumAmount(allRecords.value.filter((r) => (r.createdAt || '').slice(0, 10) === todayStr()))
)
const monthTotal = computed(() =>
  sumAmount(allRecords.value.filter((r) => (r.createdAt || '').slice(0, 7) === monthStr()))
)
const allTotal = computed(() => sumAmount(allRecords.value))

/* 新增充值 */
const dialogVisible = ref(false)
const saving = ref(false)
const DEFAULT_RECHARGE_AMOUNT = 3000

const form = ref({
  employeeId: undefined as number | undefined,
  amount: DEFAULT_RECHARGE_AMOUNT,
  remark: '',
})

const openCreate = () => {
  form.value = { employeeId: undefined, amount: DEFAULT_RECHARGE_AMOUNT, remark: '' }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!form.value.employeeId) {
    ElMessage.warning('请选择员工')
    return
  }
  if (!form.value.amount || form.value.amount <= 0) {
    ElMessage.warning('请输入有效的充值金额')
    return
  }
  saving.value = true
  try {
    await rechargeApi.create({
      employeeId: form.value.employeeId,
      amount: Number(form.value.amount),
      remark: form.value.remark?.trim() || undefined,
      operator: authStore.admin?.name,
      storeId: storeId.value ?? 0,
    })
    ElMessage.success('充值成功')
    dialogVisible.value = false
    await fetchRecords()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  fetchEmployees()
  fetchRecords()
})
</script>

<template>
  <Layout>
    <PageContainer title="充值记录" description="管理员工账户充值,实时统计充值数据。">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">新增充值</ElButton>
      </template>

      <div
        v-if="!storeId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <!-- 统计卡 -->
      <div class="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard title="今日充值" :value="money(todayTotal)" :icon="Wallet" color="primary" />
        <StatCard title="本月充值" :value="money(monthTotal)" :icon="CalendarDays" color="success" />
        <StatCard title="累计充值" :value="money(allTotal)" :icon="Coins" color="accent" />
      </div>

      <SearchBar @search="handleSearch" @reset="handleReset">
        <ElSelect
          v-model="employeeFilter"
          placeholder="选择员工"
          clearable
          filterable
          style="width: 180px"
          @change="handleSearch"
        >
          <ElOption
            v-for="emp in employees"
            :key="emp.id"
            :label="`${emp.name}（${emp.cardNo}）`"
            :value="emp.id as number"
          />
        </ElSelect>
        <ElDatePicker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width: 220px"
          @change="handleSearch"
        />
      </SearchBar>

      <div
        class="rounded-xl border border-border bg-card shadow-sm overflow-hidden"
        v-loading="loading"
      >
        <ElTable :data="pagedList" style="width: 100%">
          <ElTableColumn label="员工" min-width="160">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <div class="flex h-8 w-8 items-center justify-center rounded-full bg-primary-50 text-xs font-semibold text-primary">
                  {{ empName(row.employeeId).charAt(0) }}
                </div>
                <div>
                  <div class="text-sm font-medium text-text">{{ empName(row.employeeId) }}</div>
                  <div class="text-xs text-text-muted">{{ empMap[row.employeeId]?.cardNo || '—' }}</div>
                </div>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="充值金额" width="140" align="right">
            <template #default="{ row }">
              <span class="text-base font-semibold tabular-nums" style="color: var(--color-success)">
                +¥{{ Number(row.amount || 0).toFixed(2) }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="备注" min-width="180">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.remark || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作人" width="120">
            <template #default="{ row }">
              <span class="text-text-secondary">{{ row.operator || '—' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="充值时间" width="170">
            <template #default="{ row }">
              <span class="tabular-nums text-text-muted">{{ row.createdAt || '—' }}</span>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无充值记录" />
          </template>
        </ElTable>

        <div class="flex justify-end border-t border-border-light px-4 py-3">
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            background
          />
        </div>
      </div>

      <ElDialog
        v-model="dialogVisible"
        title="新增充值"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="form" label-width="90px" label-position="right">
          <ElFormItem label="员工" required>
            <ElSelect v-model="form.employeeId" filterable placeholder="请选择员工" style="width: 100%">
              <ElOption
                v-for="emp in employees"
                :key="emp.id"
                :label="`${emp.name}（${emp.cardNo}）`"
                :value="emp.id as number"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="充值金额" required>
            <ElInputNumber
              v-model="form.amount"
              :precision="2"
              :min="0.01"
              :step="10"
              controls-position="right"
              style="width: 100%"
            />
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput
              v-model="form.remark"
              type="textarea"
              :rows="3"
              maxlength="100"
              show-word-limit
              placeholder="选填,如:现金充值、月度补贴等"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving" @click="handleSave">确认充值</ElButton>
          </div>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
