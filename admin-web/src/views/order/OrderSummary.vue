<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElMessage,
} from 'element-plus'
import * as XLSX from 'xlsx'
import {
  Download,
  ImageDown,
  RefreshCw,
  ClipboardList,
  ChefHat,
  PackageCheck,
  Receipt,
} from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import StatCard from '@/components/StatCard.vue'
import { useAuthStore } from '@/stores/auth'
import { orderApi, storeApi } from '@/api'
import type { OrderSummary, OrderSummaryItem, Store } from '@/api/types'
import { MEAL_TYPE } from '@/constants/dict'
import { todayStr } from '@/utils/date'
import { formatMoney } from '@/utils/money'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const sid = computed(() => authStore.storeId || null)

/* ===== 筛选 ===== */
const filters = reactive({
  date: todayStr(),
  mealType: undefined as number | undefined,
})

const mealTypeOptions = Object.entries(MEAL_TYPE).map(([k, v]) => ({
  value: Number(k),
  label: v.label,
}))

const mealLabel = (m?: number | null) =>
  m != null ? (MEAL_TYPE as Record<number, { label: string }>)[m]?.label ?? '全部' : '全部'

/* ===== 数据 ===== */
const summary = ref<OrderSummary | null>(null)
const loading = ref(false)
const exporting = ref(false)

const items = computed<OrderSummaryItem[]>(() => summary.value?.items ?? [])
const totalQuantity = computed(() => summary.value?.totalQuantity ?? 0)
const totalOrders = computed(() => summary.value?.totalOrders ?? 0)
const dishCount = computed(() => summary.value?.dishCount ?? 0)

/** 总金额:Σ price × quantity */
const totalAmount = computed(() =>
  items.value
    .reduce((sum, it) => sum + Number(it.price ?? 0) * (it.quantity ?? 0), 0)
    .toFixed(2)
)

/** 小计金额 */
const subtotal = (it: OrderSummaryItem) =>
  (Number(it.price ?? 0) * (it.quantity ?? 0)).toFixed(2)

/** 金额格式化(保留模板中的 formatPrice 命名,内部复用 utils/money) */
const formatPrice = formatMoney

/* ===== 查询 ===== */
const fetchSummary = async () => {
  if (!filters.date) {
    ElMessage.warning('请选择日期')
    return
  }
  const sidVal = sid.value
  if (!sidVal) {
    summary.value = null
    return
  }
  loading.value = true
  try {
    summary.value = await orderApi.summary(sidVal, filters.date, filters.mealType)
    if (items.value.length === 0) {
      // 无数据时不报错,仅展示空态
    }
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  fetchSummary()
}

const handleReset = () => {
  filters.date = todayStr()
  filters.mealType = undefined
  fetchSummary()
}

/* ===== 导出 Excel ===== */
const handleExport = () => {
  if (items.value.length === 0) {
    ElMessage.warning('当前没有可导出的订餐汇总数据')
    return
  }
  exporting.value = true
  try {
    const mealText = mealLabel(filters.mealType)
    // 顶部信息行(厨师下料参考)
    const infoRows = [
      { '项目': '订餐汇总表', '内容': '' },
      { '项目': '门店ID', '内容': sid.value ?? '—' },
      { '项目': '日期', '内容': filters.date },
      { '项目': '餐次', '内容': mealText },
      { '项目': '菜品数', '内容': dishCount.value },
      { '项目': '总份数', '内容': totalQuantity.value },
      { '项目': '总订单数', '内容': totalOrders.value },
      { '项目': '总金额(元)', '内容': totalAmount.value },
      { '项目': '导出时间', '内容': new Date().toLocaleString('zh-CN') },
      { '项目': '', '内容': '' },
    ]

    // 明细行
    const detailRows = items.value.map((it, idx) => ({
      '序号': idx + 1,
      '菜品名称': it.dishName,
      '单价(元)': formatPrice(it.price),
      '订购份数': it.quantity,
      '订单数': it.orderCount,
      '小计金额(元)': subtotal(it),
    }))

    // 合计行
    const totalRow = {
      '序号': '',
      '菜品名称': '合计',
      '单价(元)': '',
      '订购份数': totalQuantity.value,
      '订单数': totalOrders.value,
      '小计金额(元)': totalAmount.value,
    }

    const allRows = [...infoRows, ...detailRows, totalRow]
    const ws = XLSX.utils.json_to_sheet(allRows, {
      header: ['项目', '内容', '序号', '菜品名称', '单价(元)', '订购份数', '订单数', '小计金额(元)'],
    })
    ws['!cols'] = [
      { wch: 12 }, { wch: 22 },
      { wch: 6 }, { wch: 22 }, { wch: 12 }, { wch: 12 }, { wch: 10 }, { wch: 14 },
    ]

    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '订餐汇总')
    const dateStr = filters.date.replace(/-/g, '')
    const mealStr = filters.mealType != null ? `_${mealText}` : ''
    XLSX.writeFile(wb, `订餐汇总_${dateStr}${mealStr}.xlsx`)
    ElMessage.success(`已导出 ${items.value.length} 条汇总记录`)
  } catch (e) {
    ElMessage.error('导出失败,请重试')
  } finally {
    exporting.value = false
  }
}

/* ===== 日期切换实时刷新 ===== */
watch(
  () => filters.date,
  () => {
    fetchSummary()
  }
)

/* ===== 门店名称(用于导出图片标题) ===== */
const storeName = ref<string>(`门店 #${sid.value ?? '—'}`)
const fetchStoreName = async () => {
  const sidVal = sid.value
  if (!sidVal) return
  try {
    const s: Store = await storeApi.get(sidVal)
    if (s?.name) storeName.value = s.name
  } catch {
    /* 拦截器提示 */
  }
}

/* ===== 导出 PNG:Canvas 绘制 → 弹窗预览 → 下载 ===== */
const imageDialogVisible = ref(false)
const imageDataUrl = ref('')
const imageGenerating = ref(false)

/** 在 canvas 上绘制圆角矩形 */
const roundRect = (
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) => {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

/** 绘制换行文本,返回实际绘制行数 */
const drawWrappedText = (
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  maxWidth: number,
  lineHeight: number
): number => {
  const chars = Array.from(text)
  let line = ''
  let lineCount = 0
  for (const ch of chars) {
    const test = line + ch
    if (ctx.measureText(test).width > maxWidth && line.length > 0) {
      ctx.fillText(line, x, y + lineCount * lineHeight)
      line = ch
      lineCount++
    } else {
      line = test
    }
  }
  if (line) {
    ctx.fillText(line, x, y + lineCount * lineHeight)
    lineCount++
  }
  return lineCount
}

/** 单个餐别分组数据 */
interface MealGroup {
  mealType: number
  mealLabel: string
  mealColor: string
  items: OrderSummaryItem[]
  totalQuantity: number
}

/** 收集要绘制的餐别分组:
 *  - 指定了 mealType:只画该餐别
 *  - 未指定:分别拉取早/中/晚,跳过空组
 */
const collectMealGroups = async (): Promise<MealGroup[]> => {
  const sidVal = sid.value
  if (!sidVal) return []
  const colorMap: Record<number, string> = {
    1: '#f59e0b', // 早餐-橙
    2: '#10b981', // 午餐-绿
    3: '#6366f1', // 晚餐-紫
  }
  if (filters.mealType != null) {
    const mt = filters.mealType
    const data = await orderApi.summary(sidVal, filters.date, mt)
    if (!data.items.length) return []
    return [
      {
        mealType: mt,
        mealLabel: mealLabel(mt),
        mealColor: colorMap[mt] ?? '#1a73fe',
        items: data.items,
        totalQuantity: data.totalQuantity,
      },
    ]
  }
  // 未指定餐次:并行拉取三个餐次
  const results = await Promise.all(
    [1, 2, 3].map(async (mt) => {
      const data = await orderApi.summary(sidVal, filters.date, mt).catch(() => null)
      if (!data || !data.items.length) return null
      return {
        mealType: mt,
        mealLabel: mealLabel(mt),
        mealColor: colorMap[mt] ?? '#1a73fe',
        items: data.items,
        totalQuantity: data.totalQuantity,
      } as MealGroup
    })
  )
  return results.filter((g): g is MealGroup => g !== null)
}

const generateImage = async () => {
  imageGenerating.value = true
  try {
    // 等待字体加载,避免中文首屏渲染异常
    if (document.fonts?.ready) {
      await document.fonts.ready
    }

    const groups = await collectMealGroups()
    if (groups.length === 0) {
      ElMessage.warning('当前没有可生成的订餐汇总数据')
      return
    }

    const grandTotal = groups.reduce((s, g) => s + g.totalQuantity, 0)
    const grandDishCount = groups.reduce((s, g) => s + g.items.length, 0)

    const W = 720
    const padding = 40
    const titleH = 90
    const metaH = 50
    const groupHeaderH = 38
    const tableHeaderH = 44
    const rowH = 44
    const groupGap = 22
    const footerH = 70
    const contentW = W - padding * 2

    // 计算总高度
    let totalGroupsH = 0
    groups.forEach((g, i) => {
      totalGroupsH += groupHeaderH + tableHeaderH + g.items.length * rowH
      if (i < groups.length - 1) totalGroupsH += groupGap
    })
    const H = padding + titleH + metaH + totalGroupsH + footerH + padding

    const canvas = document.createElement('canvas')
    const scale = 2 // 2x 高清
    canvas.width = W * scale
    canvas.height = H * scale
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas not supported')
    ctx.scale(scale, scale)

    // ===== 背景 =====
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, W, H)

    // ===== 顶部品牌色条 =====
    ctx.fillStyle = '#1a73fe'
    ctx.fillRect(0, 0, W, 8)

    // ===== 标题区 =====
    let y = padding
    // 餐厅名
    ctx.fillStyle = '#0f172a'
    ctx.font = 'bold 26px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.textBaseline = 'top'
    ctx.fillText(storeName.value, padding, y)
    // 副标题
    y += 36
    ctx.fillStyle = '#64748b'
    ctx.font = '15px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText('订餐汇总表', padding, y)
    // 右侧导出时间
    ctx.textAlign = 'right'
    const exportTime = new Date().toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
    ctx.fillStyle = '#94a3b8'
    ctx.font = '13px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(`导出:${exportTime}`, W - padding, y + 4)
    ctx.textAlign = 'left'

    // ===== Meta 信息:日期 / 总份数 / 菜品数 =====
    y = padding + titleH
    const metaChips = [
      { label: '日期', value: filters.date },
      { label: '总份数', value: String(grandTotal) },
      { label: '菜品数', value: String(grandDishCount) },
    ]
    let chipX = padding
    const chipH = 34
    for (const chip of metaChips) {
      const text = `${chip.label} ${chip.value}`
      ctx.font = '14px "PingFang SC", "Microsoft YaHei", sans-serif'
      const textW = ctx.measureText(text).width
      const chipW = textW + 28
      // chip 背景
      ctx.fillStyle = '#eff6ff'
      roundRect(ctx, chipX, y, chipW, chipH, 17)
      ctx.fill()
      // chip 文字
      ctx.fillStyle = '#1e40af'
      ctx.textBaseline = 'middle'
      ctx.fillText(text, chipX + 14, y + chipH / 2)
      chipX += chipW + 10
    }
    ctx.textBaseline = 'top'

    // ===== 按餐别分组绘制 =====
    y = padding + titleH + metaH
    const colNameW = Math.floor(contentW * 0.55)
    const colQtyW = contentW - colNameW

    for (let gi = 0; gi < groups.length; gi++) {
      const g = groups[gi]

      // --- 组小标题(带颜色色块 + 餐别名 + 该组小计) ---
      ctx.fillStyle = g.mealColor
      roundRect(ctx, padding, y + 8, 6, groupHeaderH - 16, 3)
      ctx.fill()
      ctx.fillStyle = '#0f172a'
      ctx.font = 'bold 18px "PingFang SC", "Microsoft YaHei", sans-serif'
      ctx.textBaseline = 'middle'
      ctx.fillText(g.mealLabel, padding + 16, y + groupHeaderH / 2)
      // 右侧该组小计
      ctx.textAlign = 'right'
      ctx.fillStyle = g.mealColor
      ctx.font = 'bold 15px "PingFang SC", "Microsoft YaHei", sans-serif'
      ctx.fillText(`共 ${g.totalQuantity} 份`, W - padding, y + groupHeaderH / 2)
      ctx.textAlign = 'left'
      ctx.textBaseline = 'top'
      y += groupHeaderH

      // --- 表头(使用该餐别的颜色) ---
      ctx.fillStyle = g.mealColor
      roundRect(ctx, padding, y, contentW, tableHeaderH, 10)
      ctx.fill()
      ctx.fillStyle = '#ffffff'
      ctx.font = 'bold 15px "PingFang SC", "Microsoft YaHei", sans-serif'
      ctx.textBaseline = 'middle'
      ctx.fillText('菜品名称', padding + 18, y + tableHeaderH / 2)
      ctx.textAlign = 'right'
      ctx.fillText('订购份数', padding + colNameW + colQtyW - 18, y + tableHeaderH / 2)
      ctx.textAlign = 'left'
      y += tableHeaderH

      // --- 数据行 ---
      for (let i = 0; i < g.items.length; i++) {
        const it = g.items[i]
        const ry = y + i * rowH
        // 斑马纹背景
        if (i % 2 === 1) {
          ctx.fillStyle = '#f8fafc'
          ctx.fillRect(padding, ry, contentW, rowH)
        }
        // 菜品名(支持换行)
        ctx.fillStyle = '#0f172a'
        ctx.font = '16px "PingFang SC", "Microsoft YaHei", sans-serif'
        ctx.textBaseline = 'middle'
        drawWrappedText(
          ctx,
          it.dishName,
          padding + 18,
          ry + rowH / 2 - 8,
          colNameW - 36,
          20
        )
        // 份数(加粗、餐别色)
        ctx.fillStyle = g.mealColor
        ctx.font = 'bold 18px "PingFang SC", "Microsoft YaHei", sans-serif'
        ctx.textAlign = 'right'
        ctx.fillText(String(it.quantity), padding + colNameW + colQtyW - 18, ry + rowH / 2)
        ctx.textAlign = 'left'
        // 行分隔线(除最后一行)
        if (i < g.items.length - 1) {
          ctx.strokeStyle = '#e2e8f0'
          ctx.lineWidth = 1
          ctx.beginPath()
          ctx.moveTo(padding, ry + rowH)
          ctx.lineTo(padding + contentW, ry + rowH)
          ctx.stroke()
        }
      }
      ctx.textBaseline = 'top'
      y += g.items.length * rowH

      // 组间距
      if (gi < groups.length - 1) {
        y += groupGap
      }
    }

    // ===== 底部合计 =====
    y += 16
    ctx.fillStyle = '#0f172a'
    ctx.font = 'bold 16px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.textAlign = 'left'
    ctx.fillText(`合计:${grandTotal} 份`, padding, y)
    ctx.textAlign = 'right'
    ctx.fillStyle = '#64748b'
    ctx.font = '13px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText('请厨师按此数量备料', W - padding, y + 4)
    ctx.textAlign = 'left'

    // 转 dataURL 并展示
    imageDataUrl.value = canvas.toDataURL('image/png')
    imageDialogVisible.value = true
  } catch (e) {
    if (import.meta.env.DEV) console.error(e)
    ElMessage.error('图片生成失败,请重试')
  } finally {
    imageGenerating.value = false
  }
}

const downloadImage = () => {
  if (!imageDataUrl.value) return
  const dateStr = filters.date.replace(/-/g, '')
  const mealStr = filters.mealType != null ? `_${mealLabel(filters.mealType)}` : ''
  const a = document.createElement('a')
  a.href = imageDataUrl.value
  a.download = `订餐汇总_${dateStr}${mealStr}.png`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  ElMessage.success('图片已下载')
}

onMounted(() => {
  fetchSummary()
  fetchStoreName()
})
</script>

<template>
  <Layout>
    <PageContainer
      title="订餐汇总"
      description="按日期与餐次汇总各菜品订购数量,15:00 截止订餐后导出 Excel 交厨师备料。"
    >
      <template #actions>
        <ElButton :icon="RefreshCw" :loading="loading" @click="fetchSummary">刷新</ElButton>
        <ElButton
          :icon="ImageDown"
          :loading="imageGenerating"
          :disabled="items.length === 0"
          @click="generateImage"
        >
          导出图片
        </ElButton>
        <ElButton
          type="primary"
          :icon="Download"
          :loading="exporting"
          :disabled="items.length === 0"
          @click="handleExport"
        >
          导出 Excel
        </ElButton>
      </template>

      <div
        v-if="!sid"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <!-- 筛选栏 -->
      <div
        class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm"
      >
        <div class="flex items-center gap-2">
          <span class="text-sm font-medium text-text-secondary">日期</span>
          <ElDatePicker
            v-model="filters.date"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            :clearable="false"
            style="width: 180px"
            @change="handleSearch"
          />
        </div>
        <div class="flex items-center gap-2">
          <span class="text-sm font-medium text-text-secondary">餐次</span>
          <ElSelect
            v-model="filters.mealType"
            placeholder="全部餐次"
            clearable
            style="width: 140px"
            @change="handleSearch"
          >
            <ElOption v-for="o in mealTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </ElSelect>
        </div>
        <ElButton type="primary" :loading="loading" @click="handleSearch">查询</ElButton>
        <ElButton @click="handleReset">重置</ElButton>

        <div class="ml-auto flex items-center gap-2 text-xs text-text-muted">
          <ChefHat class="h-4 w-4" />
          <span>次日订单截止时间:前一天 15:00</span>
        </div>
      </div>

      <!-- 统计卡片 -->
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="菜品数"
          :value="dishCount"
          :icon="ChefHat"
          color="primary"
        />
        <StatCard
          title="订购总份数"
          :value="totalQuantity"
          :icon="PackageCheck"
          color="success"
        />
        <StatCard
          title="订单总数"
          :value="totalOrders"
          :icon="Receipt"
          color="warning"
        />
        <StatCard
          title="总金额(元)"
          :value="totalAmount"
          :icon="ClipboardList"
          color="accent"
        />
      </div>

      <!-- 汇总表格 -->
      <div
        class="mt-6 overflow-hidden rounded-xl border border-border bg-card shadow-sm"
        v-loading="loading"
      >
        <div class="flex items-center justify-between border-b border-border px-5 py-3">
          <div class="flex items-center gap-2">
            <ClipboardList class="h-5 w-5 text-primary" />
            <span class="text-base font-semibold text-text">菜品订购明细</span>
          </div>
          <div class="text-sm text-text-muted">
            日期:<span class="font-medium text-text-secondary">{{ filters.date }}</span>
            <span class="mx-2">|</span>
            餐次:<ElTag size="small" type="info" class="ml-1">{{ mealLabel(filters.mealType) }}</ElTag>
          </div>
        </div>

        <ElTable
          :data="items"
          style="width: 100%"
          row-key="dishId"
          stripe
          empty-text="该日期暂无订餐数据"
        >
          <ElTableColumn label="序号" width="70" align="center" type="index" />
          <ElTableColumn label="菜品名称" min-width="220" prop="dishName">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <ChefHat class="h-4 w-4 shrink-0 text-primary" />
                <span class="font-medium text-text">{{ row.dishName }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="单价(元)" width="120" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">¥ {{ formatPrice(row.price) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订购份数" width="120" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-semibold text-primary">{{ row.quantity }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订单数" width="110" align="right">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.orderCount }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="小计金额(元)" width="140" align="right">
            <template #default="{ row }">
              <span class="tabular-nums font-medium text-text">¥ {{ subtotal(row as OrderSummaryItem) }}</span>
            </template>
          </ElTableColumn>
          <template #append>
            <div
              v-if="items.length > 0"
              class="flex justify-end border-t border-border bg-bg-secondary px-5 py-3 text-sm"
            >
              <div class="flex gap-8 tabular-nums">
                <span class="text-text-muted">
                  合计份数:<span class="font-semibold text-primary">{{ totalQuantity }}</span>
                </span>
                <span class="text-text-muted">
                  合计金额:<span class="font-semibold text-primary">¥ {{ totalAmount }}</span>
                </span>
              </div>
            </div>
          </template>
        </ElTable>
      </div>
    </PageContainer>

    <!-- 图片预览弹窗 -->
    <ElDialog
      v-model="imageDialogVisible"
      title="订餐汇总图片预览"
      width="780"
      align-center
      class="order-summary-image-dialog"
    >
      <div class="flex flex-col items-center gap-4">
        <p class="text-sm text-text-secondary">
          可直接截图,或点击下方按钮下载 PNG 图片。
        </p>
        <img
          v-if="imageDataUrl"
          :src="imageDataUrl"
          alt="订餐汇总图片"
          class="max-w-full rounded-lg border border-border shadow-sm"
          style="max-height: 60vh"
        />
      </div>
      <template #footer>
        <ElButton @click="imageDialogVisible = false">关闭</ElButton>
        <ElButton type="primary" :icon="Download" @click="downloadImage">下载图片</ElButton>
      </template>
    </ElDialog>
  </Layout>
</template>
