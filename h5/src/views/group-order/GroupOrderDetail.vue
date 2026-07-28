<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'
import { getGroupOrderDetail } from '@/api/groupOrder'
import {
  formatMoney,
  formatDate,
  formatMealType,
} from '@/composables/useFormat'
import type { GroupOrderDetail, GroupOrderItem } from '@/api/types'

defineOptions({ name: 'GroupOrderDetail' })

const route = useRoute()
const router = useRouter()

const orderId = Number(route.params.id)

const detail = ref<GroupOrderDetail | null>(null)
const loading = ref(false)
const loaded = ref(false)

/** 当前团餐 */
const order = computed(() => detail.value?.groupOrder ?? null)
const items = computed<GroupOrderItem[]>(() => detail.value?.items ?? [])

/** 团餐状态文案 */
const statusText = (s?: number): string => {
  switch (s) {
    case 1:
      return '待确认'
    case 2:
      return '已确认'
    case 3:
      return '已取消'
    case 4:
      return '已完成'
    default:
      return '待确认'
  }
}

/** 状态标签类型 */
const statusTagType = (s?: number): 'warning' | 'primary' | 'success' | 'default' => {
  if (s === 2) return 'primary'
  if (s === 4) return 'success'
  if (s === 3) return 'default'
  return 'warning'
}

/** 菜品小计 */
const itemSubtotal = (it: GroupOrderItem): number => {
  if (it.amount != null) return it.amount
  const price = it.price ?? 0
  return price * it.quantity
}

/** 合计金额 */
const totalAmount = computed<number>(() => {
  if (order.value?.totalAmount != null) return order.value.totalAmount
  return items.value.reduce((sum, it) => sum + itemSubtotal(it), 0)
})

/** 加载详情 */
const loadDetail = async (): Promise<void> => {
  if (!orderId || Number.isNaN(orderId)) {
    loaded.value = true
    return
  }
  loading.value = true
  try {
    detail.value = await getGroupOrderDetail(orderId)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
    loaded.value = true
  }
}

onMounted(() => {
  loadDetail()
})

const onBack = (): void => {
  router.back()
}
</script>

<template>
  <div class="group-order-detail">
    <van-nav-bar title="团餐详情" left-arrow @click-left="onBack" />

    <div v-if="loading" class="group-order-detail__loading">
      <van-loading size="24px">加载中...</van-loading>
    </div>

    <template v-else-if="order">
      <div class="group-order-detail__body">
        <!-- 团餐信息 -->
        <van-cell-group inset class="group-order-detail__info" title="团餐信息">
          <van-cell title="标题" :value="order.title" />
          <van-cell title="就餐日期" :value="formatDate(order.mealDate)" />
          <van-cell title="餐别" :value="formatMealType(order.mealType)" />
          <van-cell title="用餐人数" :value="`${order.headcount} 人`" />
          <van-cell
            v-if="order.contactPerson"
            title="联系人"
            :value="order.contactPerson"
          />
          <van-cell
            v-if="order.contactPhone"
            title="联系电话"
            :value="order.contactPhone"
          />
          <van-cell title="状态">
            <template #value>
              <van-tag :type="statusTagType(order.status)">
                {{ statusText(order.status) }}
              </van-tag>
            </template>
          </van-cell>
          <van-cell
            v-if="order.location"
            title="用餐地点"
            :value="order.location"
          />
          <van-cell
            v-if="order.notes"
            title="备注"
            :label="order.notes"
          />
        </van-cell-group>

        <!-- 菜品明细 -->
        <div class="card group-order-detail__items">
          <div class="group-order-detail__items-title">菜品明细</div>
          <div
            v-for="it in items"
            :key="it.id"
            class="group-order-detail__item"
          >
            <div class="group-order-detail__item-info">
              <div class="group-order-detail__item-name">
                {{ it.dishName || '菜品' }}
              </div>
              <div class="group-order-detail__item-meta">
                <span class="group-order-detail__item-price">
                  ¥{{ formatMoney(it.price ?? 0) }}
                </span>
                <span class="group-order-detail__item-qty">
                  x{{ it.quantity }}
                </span>
              </div>
            </div>
            <div class="group-order-detail__item-subtotal">
              ¥{{ formatMoney(itemSubtotal(it)) }}
            </div>
          </div>
          <div v-if="items.length === 0" class="group-order-detail__items-empty">
            暂无菜品明细
          </div>
          <div class="group-order-detail__total">
            合计:<span class="group-order-detail__total-num">
              ¥{{ formatMoney(totalAmount) }}
            </span>
          </div>
        </div>
      </div>
    </template>

    <EmptyState v-else-if="loaded" text="团餐不存在" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.group-order-detail {
  min-height: 100vh;
  padding-bottom: 16px;

  &__loading {
    padding: 48px 0;
    display: flex;
    justify-content: center;
  }

  &__body {
    padding: 12px 0;
  }

  &__info {
    margin-bottom: 12px;
  }

  // 菜品明细
  &__items {
    margin: 0 16px 12px;

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
    padding: 10px 0;
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
      font-size: 20px;
      margin-left: 4px;
    }
  }
}
</style>
