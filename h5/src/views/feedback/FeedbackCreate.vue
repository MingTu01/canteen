<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showFailToast } from 'vant'
import { useAuthStore } from '@/stores/auth'
import { createFeedback } from '@/api/feedback'
import { getMyOrders } from '@/api/order'
import {
  formatMealType,
  formatOrderStatus,
  formatMoney,
  formatDateTime,
} from '@/composables/useFormat'
import EmptyState from '@/components/EmptyState.vue'
import type { Order } from '@/api/types'

defineOptions({ name: 'FeedbackCreate' })

const router = useRouter()
const authStore = useAuthStore()

const BRAND_COLOR = '#0065fd'

const loading = ref(false)
const form = reactive({
  rating: 5,
  content: '',
  category: 1,
  orderId: null as number | null,
})

/** 反馈类型选项(图标 + 文案) */
const categories = [
  { value: 1, label: '菜品质量', icon: 'smile-comment-o', color: '#ff6b35' },
  { value: 2, label: '服务态度', icon: 'service-o', color: '#0065fd' },
  { value: 3, label: '环境卫生', icon: 'location-o', color: '#07c160' },
  { value: 4, label: '其他建议', icon: 'edit-o', color: '#ff976a' },
]

/** 评分文案 */
const rateText = computed(() => {
  switch (form.rating) {
    case 1:
      return '非常不满意'
    case 2:
      return '不满意'
    case 3:
      return '一般'
    case 4:
      return '满意'
    default:
      return '非常满意'
  }
})

/** 内容长度校验:至少 10 字 */
const contentValid = computed(() => form.content.trim().length >= 10)
const canSubmit = computed(() => form.rating > 0 && contentValid.value)

// ============ 关联订单选择 ============
const showOrderPicker = ref(false)
const orderLoading = ref(false)
const orders = ref<Order[]>([])
const selectedOrder = ref<Order | null>(null)

/** 打开订单选择弹层,拉取最近订单 */
const openOrderPicker = async (): Promise<void> => {
  showOrderPicker.value = true
  if (orders.value.length > 0) return
  if (!authStore.employeeId) return
  orderLoading.value = true
  try {
    orders.value = await getMyOrders(authStore.employeeId)
  } catch {
    /* 拦截器已提示 */
  } finally {
    orderLoading.value = false
  }
}

/** 选择订单 */
const selectOrder = (o: Order): void => {
  selectedOrder.value = o
  form.orderId = o.id
  showOrderPicker.value = false
}

/** 清除已选订单 */
const clearOrder = (): void => {
  selectedOrder.value = null
  form.orderId = null
}

/** 餐别标签类型 */
const mealTagType = (mealType: number): 'primary' | 'warning' | 'success' => {
  if (mealType === 1) return 'warning'
  if (mealType === 2) return 'primary'
  return 'success'
}

/** 订单状态标签类型 */
const statusTagType = (status: number): 'primary' | 'success' | 'default' => {
  if (status === 1) return 'primary'
  if (status === 2) return 'success'
  return 'default'
}

/** 提交反馈 */
const onSubmit = async (): Promise<void> => {
  if (!authStore.storeId || !authStore.employeeId) {
    showFailToast('请先登录')
    return
  }
  if (!form.rating) {
    showFailToast('请选择评分')
    return
  }
  if (!contentValid.value) {
    showFailToast('反馈内容至少 10 字')
    return
  }
  loading.value = true
  try {
    await createFeedback({
      storeId: authStore.storeId,
      employeeId: authStore.employeeId,
      orderId: form.orderId,
      rating: form.rating,
      content: form.content.trim(),
      category: form.category,
    })
    showSuccessToast('反馈已提交')
    router.back()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

const onBack = (): void => {
  router.back()
}
</script>

<template>
  <div class="feedback-create">
    <van-nav-bar title="提交反馈" left-arrow @click-left="onBack" />

    <div class="feedback-create__body">
      <!-- 反馈类型 -->
      <div class="card feedback-create__section">
        <div class="feedback-create__section-title">反馈类型</div>
        <van-radio-group v-model="form.category">
          <div
            v-for="c in categories"
            :key="c.value"
            class="feedback-create__radio-item"
          >
            <van-radio :name="c.value">
              <template #default>
                <div class="feedback-create__radio-label">
                  <van-icon :name="c.icon" :color="c.color" size="20" />
                  <span>{{ c.label }}</span>
                </div>
              </template>
            </van-radio>
          </div>
        </van-radio-group>
      </div>

      <!-- 星级评分 -->
      <div class="card feedback-create__section">
        <div class="feedback-create__section-title">满意度评分</div>
        <div class="feedback-create__rate">
          <van-rate v-model="form.rating" :color="BRAND_COLOR" />
          <span class="feedback-create__rate-text">{{ rateText }}</span>
        </div>
      </div>

      <!-- 关联订单(可选) -->
      <div class="card feedback-create__section">
        <div class="feedback-create__section-title">关联订单(可选)</div>
        <van-cell
          v-if="!selectedOrder"
          title="点击选择关联订单"
          is-link
          :border="false"
          @click="openOrderPicker"
        />
        <div v-else class="feedback-create__order">
          <div class="feedback-create__order-info" @click="openOrderPicker">
            <div class="feedback-create__order-head">
              <van-tag plain :type="mealTagType(selectedOrder.mealType)">
                {{ formatMealType(selectedOrder.mealType) }}
              </van-tag>
              <span class="feedback-create__order-time">
                {{ formatDateTime(selectedOrder.createdAt) }}
              </span>
            </div>
            <div class="feedback-create__order-no">
              单号:{{ selectedOrder.orderNo || selectedOrder.id }}
            </div>
            <div class="feedback-create__order-foot">
              <span class="feedback-create__order-amount">
                ¥{{ formatMoney(selectedOrder.totalAmount) }}
              </span>
              <van-tag :type="statusTagType(selectedOrder.status)">
                {{ formatOrderStatus(selectedOrder.status) }}
              </van-tag>
            </div>
          </div>
          <van-icon
            name="cross"
            size="16"
            color="#969799"
            class="feedback-create__order-clear"
            @click="clearOrder"
          />
        </div>
      </div>

      <!-- 反馈内容 -->
      <div class="card feedback-create__section">
        <div class="feedback-create__section-title">
          反馈内容
          <span class="feedback-create__section-hint">(至少 10 字)</span>
        </div>
        <van-field
          v-model="form.content"
          type="textarea"
          placeholder="请输入您的反馈内容..."
          rows="4"
          autosize
          :maxlength="500"
          show-word-limit
          :border="false"
        />
      </div>
    </div>

    <!-- 底部提交按钮 -->
    <div class="feedback-create__footer safe-area-bottom">
      <van-button
        block
        round
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="onSubmit"
      >
        提交反馈
      </van-button>
    </div>

    <!-- 关联订单选择弹层 -->
    <van-popup
      v-model:show="showOrderPicker"
      position="bottom"
      round
      closeable
      :style="{ maxHeight: '60%' }"
    >
      <div class="order-picker">
        <div class="order-picker__title">选择关联订单</div>
        <div v-if="orderLoading" class="order-picker__loading">
          <van-loading size="24px">加载中...</van-loading>
        </div>
        <EmptyState v-else-if="orders.length === 0" text="暂无订单" />
        <div v-else class="order-picker__list">
          <div
            v-for="o in orders"
            :key="o.id"
            class="order-picker__item"
            :class="{ 'order-picker__item--active': selectedOrder?.id === o.id }"
            @click="selectOrder(o)"
          >
            <div class="order-picker__item-head">
              <van-tag plain :type="mealTagType(o.mealType)">
                {{ formatMealType(o.mealType) }}
              </van-tag>
              <span class="order-picker__item-time">
                {{ formatDateTime(o.createdAt) }}
              </span>
            </div>
            <div class="order-picker__item-no">
              单号:{{ o.orderNo || o.id }}
            </div>
            <div class="order-picker__item-foot">
              <span class="order-picker__item-amount">
                ¥{{ formatMoney(o.totalAmount) }}
              </span>
              <van-tag :type="statusTagType(o.status)">
                {{ formatOrderStatus(o.status) }}
              </van-tag>
            </div>
          </div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.feedback-create {
  min-height: 100vh;
  padding-bottom: 80px;

  &__body {
    padding: 12px;
  }

  &__section {
    margin-bottom: 12px;

    &-title {
      font-size: 14px;
      font-weight: 600;
      color: $text-primary;
      margin-bottom: 12px;
      display: flex;
      align-items: center;
    }

    &-hint {
      font-size: 12px;
      font-weight: 400;
      color: $text-secondary;
      margin-left: 4px;
    }
  }

  // 反馈类型单选
  &__radio-item {
    padding: 8px 0;

    &:not(:last-child) {
      border-bottom: 1px solid $border-color;
    }
  }

  &__radio-label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    color: $text-primary;
  }

  // 评分
  &__rate {
    display: flex;
    align-items: center;
    gap: 12px;

    &-text {
      font-size: 13px;
      color: $brand-primary;
      font-weight: 500;
    }
  }

  // 关联订单
  &__order {
    display: flex;
    align-items: flex-start;
    gap: 8px;

    &-info {
      flex: 1;
      min-width: 0;
      cursor: pointer;
    }

    &-head {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    &-time {
      font-size: 12px;
      color: $text-secondary;
    }

    &-no {
      margin-top: 6px;
      font-size: 13px;
      color: $text-primary;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &-foot {
      margin-top: 6px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    &-amount {
      color: $brand-orange;
      font-weight: 700;
      font-size: 15px;
    }

    &-clear {
      flex-shrink: 0;
      padding: 4px;
      cursor: pointer;
    }
  }

  // 底部提交
  &__footer {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    padding: 12px 16px;
    background: $brand-card;
    border-top: 1px solid $brand-border;
    z-index: 10;
  }
}

// 订单选择弹层
.order-picker {
  padding: 16px;
  display: flex;
  flex-direction: column;
  min-height: 200px;

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $text-primary;
    text-align: center;
    margin-bottom: 16px;
  }

  &__loading {
    padding: 48px 0;
    display: flex;
    justify-content: center;
  }

  &__list {
    padding-bottom: 12px;
    max-height: 400px;
    overflow-y: auto;
  }

  &__item {
    padding: 12px;
    border-radius: 8px;
    border: 1px solid $border-color;
    margin-bottom: 8px;
    cursor: pointer;

    &:active {
      background: $bg-gray;
    }

    &--active {
      border-color: $brand-primary;
      background: rgba(0, 101, 253, 0.04);
    }

    &-head {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    &-time {
      font-size: 12px;
      color: $text-secondary;
    }

    &-no {
      margin-top: 6px;
      font-size: 13px;
      color: $text-primary;
    }

    &-foot {
      margin-top: 6px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    &-amount {
      color: $brand-orange;
      font-weight: 700;
      font-size: 15px;
    }
  }
}
</style>
