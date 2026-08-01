<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast, showConfirmDialog } from 'vant'
import QRCode from 'qrcode'
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
const qrDataUrl = ref<string>('')

/** 图片加载失败的菜品明细 ID 集合(触发回退到占位图标) */
const erroredImages = ref<Set<number>>(new Set())

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

/** 生成二维码 */
const genQrcode = async (text: string): Promise<void> => {
  if (!text) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(text, {
      width: 200,
      margin: 2,
      color: { dark: '#1a1a1a', light: '#ffffff' },
    })
  } catch {
    qrDataUrl.value = ''
  }
}

/** 加载订单详情 */
const loadDetail = async (): Promise<void> => {
  if (!orderId) return
  loading.value = true
  try {
    detail.value = await getOrderDetail(orderId)
    // 二维码生成统一交给下方 watch(pickupCode) 处理,避免重复调用产生竞态
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadConfig()
  loadDetail()
})

// 详情变化时重新生成二维码(loadDetail 赋值 detail 后触发,immediate 保证首次也生成)
watch(
  () => order.value?.pickupCode,
  (code) => {
    if (code) genQrcode(code)
    else qrDataUrl.value = ''
  },
  { immediate: true },
)

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

/** 复制取餐码 */
const copyCode = async (code: string): Promise<void> => {
  if (!code) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(code)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = code
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    showSuccessToast('取餐码已复制')
  } catch {
    showToast('复制失败,请手动复制')
  }
}

/** 菜品小计 */
const itemSubtotal = (it: OrderItem): number => {
  if (it.amount != null) return it.amount
  return it.price * it.quantity
}

/** 标记菜品图片加载失败,触发 v-if 回退到占位图标 */
const handleImgError = (id: number): void => {
  if (erroredImages.value.has(id)) return
  const next = new Set(erroredImages.value)
  next.add(id)
  erroredImages.value = next
}

/** 获取菜品图片地址;已失败的返回空串触发占位图标 */
const getItemImg = (it: OrderItem): string => {
  if (erroredImages.value.has(it.id)) return ''
  return it.dishImage || ''
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

        <!-- 取餐码区域(仅待取餐) -->
        <div v-if="isPending && order.pickupCode" class="order-detail__pickup">
          <div class="order-detail__pickup-inner" @click="copyCode(order.pickupCode!)">
            <div class="order-detail__pickup-label">取餐码</div>
            <div class="order-detail__pickup-code">{{ order.pickupCode }}</div>
            <div class="order-detail__pickup-tip">点击复制取餐码</div>
          </div>
          <div class="order-detail__qrcode">
            <van-image
              v-if="qrDataUrl"
              width="160"
              height="160"
              :src="qrDataUrl"
              fit="contain"
            />
            <van-loading v-else size="24px">二维码生成中...</van-loading>
          </div>
          <div class="order-detail__pickup-hint">请到取餐终端扫码取餐</div>
        </div>

        <!-- 菜品明细 -->
        <div class="card order-detail__items">
          <div class="order-detail__items-title">菜品明细</div>
          <div
            v-for="it in items"
            :key="it.id"
            class="order-detail__item"
          >
            <div class="order-detail__item-img">
              <img
                v-if="getItemImg(it)"
                :src="getItemImg(it)"
                class="order-detail__item-img-src"
                loading="lazy"
                @error="handleImgError(it.id)"
              />
              <div v-else class="order-detail__item-img-placeholder">
                <van-icon name="goods-o" size="24" color="#c8c9cc" />
              </div>
            </div>
            <div class="order-detail__item-info">
              <div class="order-detail__item-name">{{ it.dishName || '菜品' }}</div>
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

  // 取餐码区域:品牌蓝渐变背景
  &__pickup {
    margin-bottom: 12px;
    border-radius: 12px;
    overflow: hidden;
    background: linear-gradient(135deg, #0065fd 0%, #0095ff 100%);
    padding: 20px 16px;
    display: flex;
    flex-direction: column;
    align-items: center;

    &-inner {
      text-align: center;
      cursor: pointer;
    }

    &-label {
      font-size: 13px;
      color: rgba(255, 255, 255, 0.85);
    }

    &-code {
      font-size: 36px;
      font-weight: 700;
      color: #fff;
      letter-spacing: 4px;
      margin: 4px 0;
      line-height: 1.2;
    }

    &-tip {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.75);
    }

    &-hint {
      margin-top: 8px;
      font-size: 12px;
      color: rgba(255, 255, 255, 0.85);
    }
  }

  &__qrcode {
    margin-top: 12px;
    padding: 8px;
    background: #fff;
    border-radius: 8px;
    width: 176px;
    height: 176px;
    display: flex;
    align-items: center;
    justify-content: center;
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

    &-img {
      flex-shrink: 0;
      width: 60px;
      height: 60px;
      border-radius: 6px;
      overflow: hidden;
      background: $bg-gray;

      &-src {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }

      &-placeholder {
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        background: $bg-gray;
      }
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
      font-size: 18px;
      margin-left: 4px;
    }
  }

  &__info {
    margin-bottom: 12px;
  }
}
</style>
