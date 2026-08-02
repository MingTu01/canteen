<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'
import { getMyFeedback } from '@/api/feedback'
import { formatDateTime } from '@/composables/useFormat'
import type { Feedback } from '@/api/types'

defineOptions({ name: 'FeedbackList' })

const router = useRouter()

const list = ref<Feedback[]>([])
const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)

/** 反馈类型标签 */
const categoryLabels: Record<number, string> = {
  1: '菜品质量',
  2: '服务态度',
  3: '环境卫生',
  4: '其他建议',
}

const categoryLabel = (c?: number): string => {
  if (c == null) return '其他建议'
  return categoryLabels[c] || '其他建议'
}

/** 状态文案 */
const statusText = (s?: number): string => {
  switch (s) {
    case 1:
      return '待处理'
    case 2:
      return '已处理'
    case 3:
      return '已忽略'
    default:
      return '待处理'
  }
}

/** 状态标签类型:待处理=warning、已处理=success、已忽略=default */
const statusTagType = (s?: number): 'warning' | 'success' | 'default' => {
  if (s === 2) return 'success'
  if (s === 3) return 'default'
  return 'warning'
}

/** 类型标签颜色 */
const categoryTagType = (c?: number): 'primary' | 'success' | 'warning' | 'default' => {
  if (c === 1) return 'warning'
  if (c === 2) return 'primary'
  if (c === 3) return 'success'
  return 'default'
}

/** 加载列表(后端 /my 返回全部记录,无分页) */
const loadList = async (): Promise<void> => {
  loading.value = true
  try {
    list.value = (await getMyFeedback()) ?? []
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

// ============ 反馈详情弹层 ============
const detailPopup = reactive({
  show: false,
  item: null as Feedback | null,
})

/** 点击卡片:弹出详情 */
const showDetail = (item: Feedback): void => {
  detailPopup.item = item
  detailPopup.show = true
}

const goCreate = (): void => {
  router.push('/feedback/create')
}

const onBack = (): void => {
  router.back()
}

loadList()
</script>

<template>
  <div class="feedback-list">
    <van-nav-bar title="我的反馈" left-arrow @click-left="onBack" />

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <!-- 加载中 -->
      <div v-if="loading && list.length === 0" class="feedback-list__loading">
        <van-loading size="24px">加载中...</van-loading>
      </div>

      <!-- 空状态 -->
      <EmptyState
        v-else-if="loaded && list.length === 0"
        text="暂无反馈记录"
      >
        <van-button round type="primary" class="feedback-list__empty-btn" @click="goCreate">
          去提交反馈
        </van-button>
      </EmptyState>

      <!-- 列表 -->
      <div v-else class="feedback-list__list">
        <div
          v-for="item in list"
          :key="item.id"
          class="card feedback-list__item"
          @click="showDetail(item)"
        >
          <!-- 顶部:类型标签 + 时间 -->
          <div class="feedback-list__item-head">
            <van-tag plain :type="categoryTagType(item.category)">
              {{ categoryLabel(item.category) }}
            </van-tag>
            <span class="feedback-list__item-time">
              {{ formatDateTime(item.createdAt) }}
            </span>
          </div>

          <!-- 中间:评分 + 内容 -->
          <div class="feedback-list__item-rate">
            <van-rate
              :model-value="item.rating"
              readonly
              size="small"
              color="#ff6b35"
              void-color="#ebedf0"
            />
          </div>
          <div class="feedback-list__item-content text-ellipsis-2">
            {{ item.content || '无内容' }}
          </div>

          <!-- 管理员回复 -->
          <div v-if="item.reply" class="feedback-list__reply">
            <div class="feedback-list__reply-label">
              <van-icon name="chat-o" size="14" color="#969799" />
              <span>管理员回复</span>
            </div>
            <div class="feedback-list__reply-text">{{ item.reply }}</div>
          </div>

          <!-- 底部:状态标签 -->
          <div class="feedback-list__item-foot">
            <van-tag :type="statusTagType(item.status)">
              {{ statusText(item.status) }}
            </van-tag>
          </div>
        </div>
      </div>
    </van-pull-refresh>

    <!-- 反馈详情弹层 -->
    <van-popup
      v-model:show="detailPopup.show"
      position="bottom"
      round
      closeable
      :style="{ maxHeight: '80%' }"
    >
      <div v-if="detailPopup.item" class="detail-popup">
        <div class="detail-popup__title">反馈详情</div>
        <div class="detail-popup__body">
          <div class="detail-popup__row">
            <van-tag plain :type="categoryTagType(detailPopup.item.category)">
              {{ categoryLabel(detailPopup.item.category) }}
            </van-tag>
            <van-tag :type="statusTagType(detailPopup.item.status)">
              {{ statusText(detailPopup.item.status) }}
            </van-tag>
          </div>
          <div class="detail-popup__rate">
            <van-rate
              :model-value="detailPopup.item.rating"
              readonly
              color="#ff6b35"
              void-color="#ebedf0"
            />
          </div>
          <div class="detail-popup__time">
            {{ formatDateTime(detailPopup.item.createdAt) }}
          </div>
          <div class="detail-popup__content-label">反馈内容</div>
          <div class="detail-popup__content">
            {{ detailPopup.item.content || '无内容' }}
          </div>
          <template v-if="detailPopup.item.reply">
            <div class="detail-popup__content-label">管理员回复</div>
            <div class="detail-popup__reply">
              {{ detailPopup.item.reply }}
            </div>
            <div v-if="detailPopup.item.replyAt" class="detail-popup__reply-time">
              回复时间:{{ formatDateTime(detailPopup.item.replyAt) }}
            </div>
          </template>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.feedback-list {
  min-height: 100vh;

  :deep(.van-list) {
    padding: 12px;
  }

  &__empty-btn {
    margin-top: 16px;
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
    }

    &-time {
      font-size: 12px;
      color: $text-secondary;
    }

    &-rate {
      margin-top: 8px;
    }

    &-content {
      margin-top: 8px;
      font-size: 14px;
      color: $text-primary;
      line-height: 1.5;
    }

    &-foot {
      margin-top: 10px;
      padding-top: 10px;
      border-top: 1px solid $border-color;
      display: flex;
      justify-content: flex-end;
    }
  }

  // 管理员回复气泡
  &__reply {
    margin-top: 10px;
    padding: 10px;
    background: $bg-gray;
    border-radius: 8px;

    &-label {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: $text-secondary;
      margin-bottom: 6px;
    }

    &-text {
      font-size: 13px;
      color: $text-primary;
      line-height: 1.5;
    }
  }
}

// 详情弹层
.detail-popup {
  padding: 16px;
  display: flex;
  flex-direction: column;

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $text-primary;
    text-align: center;
    margin-bottom: 16px;
  }

  &__body {
    padding-bottom: env(safe-area-inset-bottom);
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  &__rate {
    margin-bottom: 8px;
  }

  &__time {
    font-size: 12px;
    color: $text-secondary;
    margin-bottom: 16px;
  }

  &__content-label {
    font-size: 13px;
    font-weight: 600;
    color: $text-primary;
    margin-top: 12px;
    margin-bottom: 6px;
  }

  &__content {
    font-size: 14px;
    color: $text-primary;
    line-height: 1.6;
    background: $bg-gray;
    padding: 12px;
    border-radius: 8px;
  }

  &__reply {
    font-size: 14px;
    color: $text-primary;
    line-height: 1.6;
    background: rgba(0, 101, 253, 0.04);
    padding: 12px;
    border-radius: 8px;
    border-left: 3px solid $brand-primary;
  }

  &__reply-time {
    font-size: 12px;
    color: $text-secondary;
    margin-top: 6px;
  }
}
</style>
