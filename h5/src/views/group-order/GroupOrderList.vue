<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'
import { getMyGroupOrders } from '@/api/groupOrder'
import {
  formatMoney,
  formatDate,
  formatMealType,
} from '@/composables/useFormat'
import type { GroupOrder } from '@/api/types'

defineOptions({ name: 'GroupOrderList' })

const router = useRouter()

const list = ref<GroupOrder[]>([])
const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)

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

/** 状态标签类型:待确认=warning、已确认=primary、已完成=success、已取消=default */
const statusTagType = (s?: number): 'warning' | 'primary' | 'success' | 'default' => {
  if (s === 2) return 'primary'
  if (s === 4) return 'success'
  if (s === 3) return 'default'
  return 'warning'
}

/** 餐别标签类型 */
const mealTagType = (mealType: number): 'primary' | 'warning' | 'success' => {
  if (mealType === 1) return 'warning'
  if (mealType === 2) return 'primary'
  return 'success'
}

/** 加载列表(后端 /my 返回全部记录,无分页) */
const loadList = async (): Promise<void> => {
  loading.value = true
  try {
    list.value = await getMyGroupOrders()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
    loaded.value = true
    refreshing.value = false
  }
}

/** 下拉刷新 */
const onRefresh = (): void => {
  refreshing.value = true
  loadList()
}

/** 跳转详情 */
const goDetail = (id: number): void => {
  router.push(`/group-order/${id}`)
}

const onBack = (): void => {
  router.back()
}

loadList()
</script>

<template>
  <div class="group-order-list">
    <van-nav-bar title="团体订餐" left-arrow @click-left="onBack" />

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <!-- 加载中 -->
      <div v-if="loading && list.length === 0" class="group-order-list__loading">
        <van-loading size="24px">加载中...</van-loading>
      </div>

      <!-- 空状态 -->
      <EmptyState
        v-else-if="loaded && list.length === 0"
        text="暂无团体订餐"
      />

      <!-- 列表 -->
      <div v-else class="group-order-list__list">
        <div
          v-for="item in list"
          :key="item.id"
          class="card group-order-list__item"
          @click="goDetail(item.id)"
        >
          <!-- 顶部:标题 + 状态 -->
          <div class="group-order-list__item-head">
            <span class="group-order-list__item-title">{{ item.title }}</span>
            <van-tag :type="statusTagType(item.status)">
              {{ statusText(item.status) }}
            </van-tag>
          </div>

          <!-- 中间:日期 + 餐别 + 人数 -->
          <div class="group-order-list__item-meta">
            <van-tag plain :type="mealTagType(item.mealType)">
              {{ formatMealType(item.mealType) }}
            </van-tag>
            <span class="group-order-list__item-date">
              {{ formatDate(item.mealDate) }}
            </span>
            <span class="group-order-list__item-count">
              {{ item.headcount }} 人
            </span>
          </div>

          <!-- 底部:总金额 -->
          <div class="group-order-list__item-foot">
            <span class="group-order-list__item-label">合计</span>
            <span class="group-order-list__item-amount">
              ¥{{ formatMoney(item.totalAmount ?? 0) }}
            </span>
          </div>
        </div>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.group-order-list {
  min-height: 100vh;

  :deep(.van-list) {
    padding: 12px;
  }

  &__item {
    margin-bottom: 12px;
    border-radius: 12px;
    padding: 12px;
    cursor: pointer;

    &:active {
      background: $bg-gray;
    }

    &-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    &-title {
      font-size: 15px;
      font-weight: 600;
      color: $text-primary;
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &-meta {
      margin-top: 10px;
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: $text-secondary;
    }

    &-date {
      font-size: 13px;
      color: $text-primary;
    }

    &-count {
      font-size: 13px;
      color: $text-secondary;
    }

    &-foot {
      margin-top: 10px;
      padding-top: 10px;
      border-top: 1px solid $border-color;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    &-label {
      font-size: 13px;
      color: $text-secondary;
    }

    &-amount {
      color: $brand-orange;
      font-weight: 700;
      font-size: 17px;
    }
  }
}
</style>
