<script setup lang="ts">
/**
 * 订单确认页
 *
 * 展示按"日期 → 餐别"两级分组的已选菜品、余额、合计,提交后跳转成功页。
 * 支持跨天订餐:遍历所有日期的所有餐别依次提交,失败时仅保留未成功的项。
 *
 * - 顶部 TopBar(返回选菜)
 * - 日期分组卡片(每个日期一张卡,内含按餐别分组的菜品)
 * - 余额 + 合计
 * - 确认订餐按钮(余额不足禁用)
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import {
  orderStore,
  resetOrderFlow,
  clearCart,
  setLastOrder,
  getCartDateGroups,
  removeCartGroup,
} from '@/store/order'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useMealConfig } from '@/composables/useMealConfig'
import { formatMoney } from '@/composables/useFormat'
import { mealTypeLabel, parseDateKey, relativeLabel, pad2 } from '@/utils'
import TopBar from '@/components/TopBar.vue'
import BigButton from '@/components/BigButton.vue'
import Modal from '@/components/Modal.vue'

const router = useRouter()
const submitting = ref(false)

useIdleTimer(() => {
  resetOrderFlow()
  router.replace('/order')
})

const emp = computed(() => orderStore.employee)

/** 跨天购物车按"日期 → 餐别"两级分组 */
const dateGroups = computed(() => getCartDateGroups())

/** 日期格式化:07-27 今天 */
const formatDate = (date: string): string => {
  const d = parseDateKey(date)
  const rel = relativeLabel(date)
  return rel
    ? `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${rel}`
    : `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

const total = computed(() =>
  dateGroups.value.reduce((s, g) => s + g.dateSubtotal, 0),
)
const balance = computed(() => Number(emp.value?.balance ?? 0))
const insufficient = computed(() => total.value > balance.value)

const { mealBadgeStyle, mealIconMap, mealIconColor } = useMealConfig()

/* ============ 下单结果弹窗 ============ */
const showResultModal = ref(false)
const resultTitle = ref('')
const resultMessages = ref<string[]>([])
const resultVariant = ref<'info' | 'success' | 'danger' | 'warning'>('warning')
const resultConfirmText = ref('确认')

/* 组件卸载标志:submit 是 for-of 串行 await,卸载后立即停止避免覆盖新员工状态 */
const unmounted = ref(false)
onBeforeUnmount(() => { unmounted.value = true })

const submit = async () => {
  if (dateGroups.value.length === 0 || insufficient.value || submitting.value) return
  submitting.value = true
  try {
    const empData = emp.value
    if (!empData) return
    // 成功的 (date|mealType) 列表
    const successKeys: string[] = []
    /** 失败项:{key, message, alreadyOrdered} 已订餐的标记后需清理购物车 */
    const failures: { key: string; message: string; alreadyOrdered: boolean }[] = []

    for (const dg of dateGroups.value) {
      for (const mg of dg.meals) {
        // 组件卸载后立即停止循环,避免覆盖新员工状态
        if (unmounted.value) return
        const key = `${dg.date}|${mg.mealType}`
        try {
          await api.post('/order', {
            storeId: empData.storeId,
            employeeId: empData.id,
            date: dg.date,
            mealType: mg.mealType,
            items: mg.items.map((i) => ({ dishId: i.dishId, quantity: i.quantity })),
          })
          successKeys.push(key)
        } catch (err) {
          const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '下单失败'
          // "已下单"视为已订餐成功,清理该餐别购物车,不计入失败列表
          const alreadyOrdered = msg.includes('已下单') || msg.includes('已订餐')
          failures.push({ key, message: msg, alreadyOrdered })
        }
      }
    }
    // 卸载后立即停止,不修改全局状态
    if (unmounted.value) return

    // 全部成功:记录 lastOrder、清空购物车、刷新余额,然后用弹窗提示(不跳转结算页)
    if (successKeys.length > 0 && failures.length === 0) {
      setLastOrder(total.value)
      clearCart()
      // 刷新员工余额
      try {
        const resp = await api.get(`/terminal/employee/${encodeURIComponent(empData.cardNo)}`)
        if (unmounted.value) return
        if (resp.data.code === 200 && resp.data.data) {
          orderStore.employee = { ...empData, ...resp.data.data }
        }
      } catch {
        /* 余额刷新失败不影响下单结果 */
      }
      // 用成功弹窗提示,确认后留在确认页(购物车已空时自动回选菜页)
      const labels = successKeys.map((k) => {
        const [d, m] = k.split('|')
        return `${formatDate(d)} ${mealTypeLabel(Number(m))}`
      })
      resultTitle.value = '下单完成'
      resultMessages.value = [
        `已成功下单 ${successKeys.length} 项`,
        labels.join('、'),
      ]
      resultVariant.value = 'success'
      resultConfirmText.value = '确认'
      showResultModal.value = true
      return
    }

    // 部分失败或全部失败:
    // 1) 已成功的 group 从购物车移除
    for (const key of successKeys) {
      const [date, mtStr] = key.split('|')
      removeCartGroup(date, Number(mtStr))
    }
    // 2) "已订餐"的 group 也从购物车移除(已下单,无需重试)
    const alreadyOrderedKeys = failures.filter((f) => f.alreadyOrdered).map((f) => f.key)
    for (const key of alreadyOrderedKeys) {
      const [date, mtStr] = key.split('|')
      removeCartGroup(date, Number(mtStr))
    }
    // 3) 真正失败的项(余额不足/菜品下架等)保留在购物车供重试
    const realFailures = failures.filter((f) => !f.alreadyOrdered)

    // 构造弹窗内容
    const lines: string[] = []
    if (successKeys.length > 0) {
      lines.push(`已成功下单 ${successKeys.length} 项`)
    }
    if (alreadyOrderedKeys.length > 0) {
      const labels = alreadyOrderedKeys.map((k) => {
        const [d, m] = k.split('|')
        return `${formatDate(d)} ${mealTypeLabel(Number(m))}`
      })
      lines.push(`已订餐(自动清理):${labels.join('、')}`)
    }
    if (realFailures.length > 0) {
      lines.push('失败项:')
      for (const f of realFailures) {
        const [d, m] = f.key.split('|')
        lines.push(`  ${formatDate(d)} ${mealTypeLabel(Number(m))}:${f.message}`)
      }
      lines.push('失败项已保留,可修改后重试')
    }

    // 弹窗:确认后留在确认页(不跳转结算页)
    if (realFailures.length === 0 && successKeys.length > 0) {
      // 仅有成功 + 已订餐,无真正失败:提示后留在确认页
      resultTitle.value = '下单完成'
      resultVariant.value = 'success'
      resultConfirmText.value = '确认'
    } else if (realFailures.length > 0 && successKeys.length > 0) {
      resultTitle.value = '部分下单失败'
      resultVariant.value = 'warning'
      resultConfirmText.value = '知道了'
    } else {
      // 全部失败
      resultTitle.value = '下单失败'
      resultVariant.value = 'danger'
      resultConfirmText.value = '知道了'
    }
    resultMessages.value = lines
    showResultModal.value = true
  } catch (err) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '下单失败,请重试'
    resultTitle.value = '下单失败'
    resultMessages.value = [msg]
    resultVariant.value = 'danger'
    resultConfirmText.value = '知道了'
    showResultModal.value = true
  } finally {
    submitting.value = false
  }
}

/** 弹窗确认后留在确认页(若购物车已空则回选菜页) */
const onResultConfirm = () => {
  showResultModal.value = false
  if (dateGroups.value.length === 0) {
    router.replace('/order/select')
  }
}

// 缺数据时重定向(在 onMounted 中执行,确保 router 就绪)
onMounted(() => {
  if (!emp.value) {
    router.replace('/order')
  } else if (dateGroups.value.length === 0) {
    router.replace('/order/select')
  }
})
</script>

<template>
  <main class="confirm">
    <TopBar title="确认订单" @back="router.push('/order/select')" />

    <div class="confirm__content no-scrollbar">
      <!-- 日期分组卡片(每个日期一张卡) -->
      <div
        v-for="dg in dateGroups"
        :key="dg.date"
        class="confirm__date-group"
      >
        <!-- 日期标题 + 该日期小计 -->
        <div class="confirm__date-head">
          <span class="confirm__date-title">{{ formatDate(dg.date) }}</span>
          <span class="confirm__date-subtotal">¥{{ formatMoney(dg.dateSubtotal) }}</span>
        </div>

        <!-- 该日期下按餐别分组 -->
        <div
          v-for="mg in dg.meals"
          :key="`${dg.date}-${mg.mealType}`"
          class="confirm__meal-group"
        >
          <div class="confirm__meal-head">
            <div class="confirm__badge" :style="mealBadgeStyle(mg.mealType)">
              <component
                :is="mealIconMap[mg.mealType]"
                :size="16"
                :stroke-width="2.5"
                :color="mealIconColor(mg.mealType)"
              />
              <span>{{ mealTypeLabel(mg.mealType) }}</span>
            </div>
            <span class="confirm__meal-subtotal">¥{{ formatMoney(mg.subtotal) }}</span>
          </div>
          <div class="confirm__items">
            <div v-for="it in mg.items" :key="`${it.dishId}-${it.mealType}`" class="confirm__item">
              <span class="confirm__dish-name">
                {{ it.name }}
                <span v-if="Number(it.quantity || 1) > 1" class="confirm__qty">×{{ it.quantity }}</span>
              </span>
              <span class="confirm__dish-price">¥{{ formatMoney(Number(it.price) * Number(it.quantity || 1)) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 余额 -->
      <div class="confirm__balance">
        <span>账户余额</span>
        <span class="confirm__balance-num">¥{{ formatMoney(balance) }}</span>
      </div>

      <!-- 合计 -->
      <div class="confirm__total">
        <span>合计</span>
        <span class="confirm__total-num">¥{{ formatMoney(total) }}</span>
      </div>

      <!-- 余额不足提示 -->
      <p v-if="insufficient" class="confirm__warn">余额不足,请联系管理员充值</p>

      <BigButton
        variant="primary"
        size="xl"
        block
        :loading="submitting"
        :disabled="insufficient || dateGroups.length === 0"
        @click="submit"
      >
        {{ submitting ? '提交中...' : '确认订餐' }}
      </BigButton>
    </div>

    <!-- 下单结果弹窗(确认后留在确认页,不跳转结算页) -->
    <Modal
      v-model="showResultModal"
      :title="resultTitle"
      :message="resultMessages"
      :variant="resultVariant"
      :confirm-text="resultConfirmText"
      :cancel-text="''"
      :close-on-overlay="false"
      @confirm="onResultConfirm"
    />
  </main>
</template>

<style scoped>
.confirm {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  /* 内容页:不显示品牌背景,用不透明白色遮住全局背景 */
  background: var(--doubao-background);
}
.confirm__content {
  flex: 1;
  overflow-y: auto;
  min-width: 0;
  padding: 24px;
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

/* 日期分组卡片 */
.confirm__date-group {
  padding: 20px 24px;
  margin-bottom: 20px;
  border-radius: var(--doubao-radius);
  background: var(--doubao-card);
  border: 1.5px solid var(--doubao-border);
}
.confirm__date-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--doubao-border);
  margin-bottom: 12px;
}
.confirm__date-title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
}
.confirm__date-subtotal {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-muted-foreground);
}

/* 餐别分组 */
.confirm__meal-group {
  padding: 12px 0;
}
.confirm__meal-group + .confirm__meal-group {
  border-top: 1px dashed var(--doubao-border);
}
.confirm__meal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.confirm__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid;
  font-size: var(--fs-base);
  font-weight: 700;
}
.confirm__meal-subtotal {
  font-size: var(--fs-sm);
  color: var(--doubao-muted-foreground);
  font-weight: 400;
}
.confirm__items {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.confirm__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 20px;
  font-weight: 600;
  color: var(--doubao-card-foreground);
  padding: 6px 0;
}
.confirm__dish-name {
  font-weight: 700;
}
.confirm__dish-price {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.confirm__qty {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--doubao-muted);
  color: var(--doubao-secondary-foreground);
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.confirm__balance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  margin-bottom: 20px;
  border-radius: var(--doubao-radius);
  background: var(--doubao-muted);
  font-size: var(--fs-lg);
  color: var(--doubao-secondary-foreground);
}
.confirm__balance-num {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
}

.confirm__total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  margin-bottom: 24px;
  border-radius: var(--doubao-radius);
  background: var(--doubao-accent);
}
.confirm__total span:first-child {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--doubao-foreground);
}
.confirm__total-num {
  font-size: var(--fs-2xl);
  font-weight: 700;
  color: var(--doubao-primary);
}

.confirm__warn {
  margin: 0 0 16px;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--doubao-destructive);
}
</style>
