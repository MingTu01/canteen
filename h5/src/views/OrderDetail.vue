<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showConfirmDialog } from 'vant'
import ChiliIcon from '@/components/ChiliIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { getOrderDetail, cancelOrder } from '@/api/order'
import {
  formatMealType,
  formatOrderStatus,
  formatMoney,
  formatDate,
  formatDateTime,
} from '@/composables/useFormat'
import { useOrderConfig } from '@/composables/useOrderConfig'
import type { OrderDetail, OrderItem } from '@/api/types'

defineOptions({ name: 'OrderDetail' })

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { loadConfig, isCancellableByDeadline } = useOrderConfig()

const detail = ref<OrderDetail | null>(null)
const loading = ref(false)

const orderId = Number(route.params.id)

/** 当前订单 */
const order = computed(() => detail.value?.order ?? null)
const items = computed<OrderItem[]>(() => detail.value?.items ?? [])

/** 步骤条当前激活索引 */
const stepActive = computed<number>(() => {
  const s = order.value?.status
  if (s === 1) return 1 // 待取餐:停在第2步
  if (s === 2) return 3 // 已完成:全部完成
  return 0
})

/** 是否已取消 */
const isCancelled = computed<boolean>(() => order.value?.status === 3)

/** 是否待取餐 */
const isPending = computed<boolean>(() => order.value?.status === 1)

/** 是否在取消截止时间内(过截止时间不允许取消) */
const canCancel = computed<boolean>(() => {
  if (!order.value?.date) return false
  return isCancellableByDeadline(order.value.date, new Date())
})

/** 加载订单详情 */
const loadDetail = async (): Promise<void> => {
  if (!orderId) return
  loading.value = true
  try {
    detail.value = await getOrderDetail(orderId)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadConfig(authStore.storeId)
  loadDetail()
})

/** 返回 */
const onBack = (): void => {
  router.back()
}

/** 取消订单(二次确认) */
const onCancel = (): void => {
  showConfirmDialog({
    title: '取消订单',
    message: '确定要取消该订单吗?取消后金额将退回余额。',
    confirmButtonText: '确定取消',
    cancelButtonText: '再想想',
    confirmButtonColor: '#ee0a24',
  })
    .then(async () => {
      try {
        await cancelOrder(orderId)
        showSuccessToast('订单已取消')
        // 取消后金额退回余额,刷新员工信息以更新余额显示
        await authStore.refreshEmployee()
        loadDetail()
      } catch {
        /* 拦截器已提示 */
      }
    })
    .catch(() => {
      /* 取消 */
    })
}

/** 再来一单 / 重新下单 */
const onReorder = (): void => {
  router.push('/order')
}

/** 菜品小计 */
const itemSubtotal = (it: OrderItem): number => {
  if (it.amount != null) return it.amount
  return it.price * it.quantity
}

/** 状态文字 */
const statusText = computed<string>(() => formatOrderStatus(order.value?.status))
</script>

<template>
  <div class="order-detail">
    <van-nav-bar title="订单详情" left-arrow @click-left="onBack" />

    <div v-if="loading" class="order-detail__loading">
      <van-loading size="24px">加载中...</van-loading>
    </div>

    <template v-else-if="order">
      <div class="order-detail__body">
        <!-- 订单状态步骤条 -->
        <div class="card order-detail__status">
          <van-steps
            v-if="!isCancelled"
            :active="stepActive"
            active-color="#0065fd"
          >
            <van-step>下单</van-step>
            <van-step>待取餐</van-step>
            <van-step>已完成</van-step>
          </van-steps>
          <div v-else class="order-detail__cancelled">
            <van-icon name="close" size="24" color="#969799" />
            <span class="order-detail__cancelled-text">订单已取消</span>
          </div>
        </div>

        <!-- 菜品明细 -->
        <div class="card order-detail__items">
          <div class="order-detail__items-title">菜品明细</div>
          <div
            v-for="it in items"
            :key="it.id"
            class="order-detail__item"
          >
            <div class="order-detail__item-info">
              <div class="order-detail__item-name">
                <span>{{ it.dishName || '菜品' }}</span>
                <span
                  v-if="it.spiceLevel && it.spiceLevel > 0"
                  class="order-detail__item-spice"
                >
                  <span class="order-detail__item-spice-label">辣</span>
                  <ChiliIcon
                    v-for="n in it.spiceLevel"
                    :key="n"
                    :size="13"
                  />
                </span>
              </div>
              <div class="order-detail__item-meta">
                <span class="order-detail__item-price">¥{{ formatMoney(it.price) }}</span>
                <span class="order-detail__item-qty">x{{ it.quantity }}</span>
              </div>
            </div>
            <div class="order-detail__item-subtotal">
              ¥{{ formatMoney(itemSubtotal(it)) }}
            </div>
          </div>
          <div v-if="items.length === 0" class="order-detail__items-empty">
            暂无菜品明细
          </div>
          <div class="order-detail__total">
            合计:<span class="order-detail__total-num">¥{{ formatMoney(order.totalAmount) }}</span>
          </div>
        </div>

        <!-- 订单信息 -->
        <van-cell-group inset class="order-detail__info" title="订单信息">
          <van-cell title="订单编号" :value="order.orderNo || String(order.id)" />
          <van-cell title="下单时间" :value="formatDateTime(order.createdAt)" />
          <van-cell title="餐别" :value="formatMealType(order.mealType)" />
          <van-cell title="就餐日期" :value="formatDate(order.date)" />
          <van-cell title="订单状态">
            <template #value>
              <van-tag
                :type="order.status === 1 ? 'primary' : order.status === 2 ? 'success' : 'default'"
              >
                {{ statusText }}
              </van-tag>
            </template>
          </van-cell>
        </van-cell-group>
      </div>

      <!-- 底部操作栏 -->
      <van-action-bar>
        <van-action-bar-button
          v-if="isPending && canCancel"
          type="danger"
          plain
          text="取消订单"
          @click="onCancel"
        />
        <van-action-bar-button
          v-else-if="order.status === 2"
          type="primary"
          text="再来一单"
          @click="onReorder"
        />
        <van-action-bar-button
          v-else-if="isCancelled"
          type="primary"
          text="重新下单"
          @click="onReorder"
        />
      </van-action-bar>
    </template>

    <EmptyState v-else text="订单不存在" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.order-detail {
  min-height: 100vh;
  padding-bottom: 60px;

  &__loading {
    padding: 48px 0;
    display: flex;
    justify-content: center;
  }

  &__body {
    padding: 12px;
  }

  &__status {
    margin-bottom: 12px;
    padding: 16px 12px;
  }

  &__cancelled {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 8px 0;

    &-text {
      font-size: 15px;
      color: $text-secondary;
    }
  }

  // 菜品明细
  &__items {
    margin-bottom: 12px;

    &-title {
      font-size: 15px;
      font-weight: 600;
      color: $text-primary;
      margin-bottom: 12px;
    }

    &-empty {
      text-align: center;
      color: $text-secondary;
      font-size: 13px;
      padding: 16px 0;
    }
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px solid $border-color;

    &:last-child {
      border-bottom: none;
    }

    &-info {
      flex: 1;
      min-width: 0;
    }

    &-name {
      font-size: 14px;
      color: $text-primary;
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: flex;
      align-items: center;
      gap: 4px;
    }

    &-spice {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      flex-shrink: 0;
      padding: 2px 5px;
      background: rgba(239, 68, 68, 0.08);
      border-radius: 6px;
      color: #ef4444;
      line-height: 1;
    }

    &-spice-label {
      font-size: 11px;
      font-weight: 700;
      line-height: 1;
    }

    &-meta {
      margin-top: 4px;
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: $text-secondary;
    }

    &-price {
      color: $text-secondary;
    }

    &-qty {
      color: $text-secondary;
    }

    &-subtotal {
      font-size: 14px;
      font-weight: 600;
      color: $brand-orange;
      flex-shrink: 0;
    }
  }

  &__total {
    text-align: right;
    padding-top: 12px;
    margin-top: 4px;
    border-top: 1px solid $border-color;
    font-size: 14px;
    color: $text-primary;

    &-num {
      color: $brand-orange;
      font-weight: 700;
      font-size: 18px;
      margin-left: 4px;
    }
  }

  &__info {
    margin-bottom: 12px;
  }
}

/* 取消订单按钮:plain danger 模式下 vant 未正确覆盖背景,
   导致红底红字看不到文字,强制背景透明 + 文字红色 */
:deep(.van-action-bar-button--danger.van-button--plain) {
  color: #{$brand-danger} !important;
  background: transparent !important;
}
</style>
