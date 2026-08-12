<script setup lang="ts">
import { ref, computed, watch, onMounted, onActivated, onUnmounted, nextTick } from 'vue'

import { useRouter } from 'vue-router'
import { showImagePreview } from 'vant'
import {
  Megaphone,
  UtensilsCrossed,
  ClipboardList,
  MessageSquare,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useBrandingStore } from '@/stores/branding'
import { getStoreNotifications } from '@/api/notification'
import { formatDate } from '@/composables/useFormat'
import { getCachedImage } from '@/utils/imageCache'
import type { Notification } from '@/api/types'

defineOptions({ name: 'Home' })

const router = useRouter()
const authStore = useAuthStore()
const brandingStore = useBrandingStore()

/** 品牌信息(logo / 名称 / 描述) */
const branding = computed(() => brandingStore.branding)

/** 通知列表 */
const notifications = ref<Notification[]>([])

/** 加载状态 */
const notificationLoading = ref(true)

/** 标记首次挂载,避免 onMounted + onActivated 双重触发重复请求 */
let firstMount = true

/** 通知公告轮播:每条单独右往左滚动播放,播完切换下一条,循环播放 */
const currentNoticeIndex = ref(0)
const currentNotice = computed(
  () => notifications.value[currentNoticeIndex.value] ?? { title: '' },
)

/** 当前条滚动动画时长(根据文本长度自适应,越长滚动越久) */
const noticeScrollDuration = computed(() => {
  const len = currentNotice.value?.title?.length ?? 0
  // 基础 6 秒 + 每个字 0.4 秒,最短 6s 最长 20s
  return Math.min(20, Math.max(6, 6 + len * 0.4))
})

/** 监听当前条滚动结束(animationiteration 事件触发切下一条) */
const onNoticeScrollEnd = (): void => {
  if (notifications.value.length <= 1) return
  currentNoticeIndex.value =
    (currentNoticeIndex.value + 1) % notifications.value.length
}

/** ticker 视口实际宽度(作为滚动起始偏移,保证从最右边出现) */
const tickerViewportRef = ref<HTMLElement | null>(null)
const tickerViewportWidth = ref(0)
let tickerResizeObserver: ResizeObserver | null = null

/** 测量视口宽度并更新 */
const measureTickerViewport = (): void => {
  if (tickerViewportRef.value) {
    tickerViewportWidth.value = tickerViewportRef.value.clientWidth
  }
}

/** 公告列表(type=3):展示标题 + 图片 + 内容,点击弹出详情 */
const announcementList = computed(() =>
  notifications.value.filter((n) => n.type === 3).slice(0, 10),
)

/** 活动列表(type=4):展示标题 + 图片 + 内容,点击弹出详情 */
const activityList = computed(() =>
  notifications.value.filter((n) => n.type === 4).slice(0, 10),
)

/** 其他通知(非公告非活动):保留在原"公司通知"列表 */
const noticeList = computed(() =>
  notifications.value.filter((n) => n.type !== 3 && n.type !== 4).slice(0, 5),
)

/** 通知详情弹窗(展示标题 + 图片 + 内容) */
const noticeDetail = ref<Notification | null>(null)
const showNoticePopup = ref(false)

/** 图片缓存 Map（通知/公告图片，id → blob URL 或原 URL） */
const cachedNoticeImages = ref<Map<number, string>>(new Map())

/** 批量预加载通知图片到缓存 Map */
const refreshNoticeImages = async (): Promise<void> => {
  const map = new Map<number, string>()
  await Promise.all(
    notifications.value.map(async (n) => {
      if (!n.imageUrl) return
      if (/^(https?:)?\/\//.test(n.imageUrl) || n.imageUrl.startsWith('data:')) {
        map.set(n.id, n.imageUrl)
        return
      }
      map.set(n.id, await getCachedImage(n.imageUrl))
    }),
  )
  cachedNoticeImages.value = map
}

/** 获取通知图片地址(缓存后) */
const getNoticeImage = (item: Notification): string => {
  return cachedNoticeImages.value.get(item.id) || item.imageUrl || ''
}

/** 图片预览(点击放大) */
const previewImage = (url: string): void => {
  if (!url) return
  showImagePreview([url])
}

/** 快捷入口配置(对齐模板 Lucide 图标) */
interface ShortcutItem {
  key: string
  icon: typeof UtensilsCrossed
  text: string
  path: string
}
const shortcuts: ShortcutItem[] = [
  { key: 'order', icon: UtensilsCrossed, text: '去订餐', path: '/order' },
  { key: 'orders', icon: ClipboardList, text: '我的订单', path: '/orders' },
  { key: 'feedback', icon: MessageSquare, text: '提交反馈', path: '/feedback/create' },
]

/** 通知类型标签:根据 type 字段返回文案与配色变体;无 type 默认"公告" */
interface NoticeTag {
  label: string
  variant: 'accent' | 'muted'
}
const noticeTag = (item: Notification): NoticeTag => {
  switch (item.type) {
    case 1:
      return { label: '滚动通知', variant: 'accent' }
    case 3:
      return { label: '公告', variant: 'muted' }
    default:
      return { label: '公告', variant: 'muted' }
  }
}

/** 跳转 */
const go = (path: string): void => {
  router.push(path)
}

/** 展示通知详情(标题 + 图片 + 内容,弹层内可点击放大图片) */
const showNoticeDetail = (item: Notification): void => {
  noticeDetail.value = item
  showNoticePopup.value = true
}

/** 通知公告条点击:打开当前轮播通知的详情弹层 */
const onNoticeBarClick = (): void => {
  const current = notifications.value[currentNoticeIndex.value]
  if (!current) return
  showNoticeDetail(current)
}

/** 通知列表项点击:打开详情弹层 */
const onNoticeItemClick = (item: Notification): void => {
  showNoticeDetail(item)
}

// 通知列表变化时异步刷新图片缓存 Map
watch(notifications, refreshNoticeImages, { immediate: true })

// 通知列表变化时重置到第一条
watch(notifications, () => {
  currentNoticeIndex.value = 0
})

// 通知出现后 DOM 渲染完成,测量 ticker 视口宽度(此时 v-if 才为 true)
watch(
  () => notifications.value.length > 0,
  (has) => {
    if (has) {
      nextTick(measureTickerViewport)
    }
  },
)

/** 拉取通知 */
const loadNotifications = async (storeId: number): Promise<void> => {
  notificationLoading.value = true
  try {
    // 后端在无通知时可能返回 null,兜底为空数组避免后续 .length / .map 抛错
    notifications.value = (await getStoreNotifications(storeId)) ?? []
  } catch {
    /* 拦截器已 toast */
  } finally {
    notificationLoading.value = false
  }
}

onMounted(async () => {
  // 测量 ticker 视口宽度(用于滚动起始位置:从最右边出现)
  measureTickerViewport()
  if (tickerViewportRef.value && 'ResizeObserver' in window) {
    tickerResizeObserver = new ResizeObserver(measureTickerViewport)
    tickerResizeObserver.observe(tickerViewportRef.value)
  }

  const storeId = authStore.storeId
  if (!storeId) return

  // 并行拉取:品牌信息(秒开缓存)、通知
  await Promise.all([
    brandingStore.fetchBranding(storeId),
    loadNotifications(storeId),
  ])
})

// keep-alive 重新激活时刷新通知,避免展示过期数据
onActivated(() => {
  // keep-alive 首次挂载时 onMounted + onActivated 均触发,跳过首次避免重复请求
  if (firstMount) {
    firstMount = false
    return
  }
  const storeId = authStore.storeId
  if (!storeId) return
  loadNotifications(storeId)
})

// 清理 ResizeObserver 避免内存泄漏
onUnmounted(() => {
  if (tickerResizeObserver) {
    tickerResizeObserver.disconnect()
    tickerResizeObserver = null
  }
})
</script>

<template>
  <div class="home pt-safe">
    <!-- 顶部品牌标题模块:logo + 食堂名称(居中) -->
    <header class="home__brand-header">
      <div class="home__brand-logo">
        <img
          v-if="branding?.logoUrl"
          :src="branding.logoUrl"
          :alt="branding?.name || '食堂'"
        />
        <UtensilsCrossed v-else :size="22" :stroke-width="2.2" />
      </div>
      <h1 class="home__brand-name">{{ branding?.name || '企业食堂' }}</h1>
    </header>

    <!-- 通知轮播栏:每条单独右往左滚动,播完切下一条,循环播放 -->
    <div v-if="notifications.length > 0" class="home__ticker" @click="onNoticeBarClick">
      <div class="home__ticker-fixed">
        <Megaphone :size="16" :stroke-width="2" class="home__ticker-icon" />
        <span class="home__ticker-label">通知</span>
      </div>
      <div ref="tickerViewportRef" class="home__ticker-viewport">
        <div
          :key="currentNoticeIndex"
          class="home__ticker-track"
          :style="{
            '--duration': `${noticeScrollDuration}s`,
            '--vp-w': `${tickerViewportWidth}px`,
          }"
          @animationend="onNoticeScrollEnd"
        >
          <span class="home__ticker-text">{{ currentNotice.title }}</span>
        </div>
      </div>
    </div>

    <!-- 内容区 -->
    <div class="home__content">
      <!-- 快捷入口网格 -->
      <section class="home__quick card">
        <div class="home__quick-grid">
          <button
            v-for="item in shortcuts"
            :key="item.key"
            type="button"
            class="home__quick-item"
            @click="go(item.path)"
          >
            <component :is="item.icon" :size="28" :stroke-width="2" class="home__quick-icon" />
            <span class="home__quick-text">{{ item.text }}</span>
          </button>
        </div>
      </section>

      <!-- 公告(展示标题 + 图片 + 内容,点击弹出详情) -->
      <section v-if="announcementList.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">公告</h2>
        </div>
        <div class="home__notice-list">
          <div
            v-for="item in announcementList"
            :key="item.id"
            class="home__announcement-card"
            @click="showNoticeDetail(item)"
          >
            <div class="home__announcement-top">
              <h3 class="home__announcement-title">{{ item.title }}</h3>
              <span class="home__notice-tag home__notice-tag--muted">公告</span>
            </div>
            <div
              v-if="getNoticeImage(item)"
              class="home__announcement-image"
              @click.stop="previewImage(getNoticeImage(item))"
            >
              <img :src="getNoticeImage(item)" :alt="item.title" loading="lazy" />
            </div>
            <p v-if="item.content" class="home__announcement-content">{{ item.content }}</p>
          </div>
        </div>
      </section>

      <!-- 活动(展示标题 + 图片 + 内容,点击弹出详情) -->
      <section v-if="activityList.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">活动</h2>
        </div>
        <div class="home__notice-list">
          <div
            v-for="item in activityList"
            :key="item.id"
            class="home__announcement-card"
            @click="showNoticeDetail(item)"
          >
            <div class="home__announcement-top">
              <h3 class="home__announcement-title">{{ item.title }}</h3>
              <span class="home__notice-tag home__notice-tag--accent">活动</span>
            </div>
            <div
              v-if="getNoticeImage(item)"
              class="home__announcement-image"
              @click.stop="previewImage(getNoticeImage(item))"
            >
              <img :src="getNoticeImage(item)" :alt="item.title" loading="lazy" />
            </div>
            <p v-if="item.content" class="home__announcement-content">{{ item.content }}</p>
          </div>
        </div>
      </section>

      <!-- 公司通知(非公告非活动的其他通知) -->
      <section v-if="!notificationLoading && noticeList.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">公司通知</h2>
        </div>

        <div class="home__notice-list">
          <div
            v-for="item in noticeList"
            :key="item.id"
            class="home__notice-card"
            @click="onNoticeItemClick(item)"
          >
            <div class="home__notice-top">
              <h3 class="home__notice-title">{{ item.title }}</h3>
              <span
                class="home__notice-tag"
                :class="`home__notice-tag--${noticeTag(item).variant}`"
              >
                {{ noticeTag(item).label }}
              </span>
            </div>
            <p class="home__notice-date">{{ formatDate(item.createdAt || item.startDate) }}</p>
          </div>
        </div>
      </section>

      <!-- CTA 去订餐 -->
      <button type="button" class="home__cta" @click="go('/order')">
        <UtensilsCrossed :size="20" :stroke-width="2" />
        <span>去订餐</span>
      </button>
    </div>

    <!-- 通知详情弹层(标题 + 图片 + 内容;仅通过按钮/关闭图标关闭,不响应遮罩点击) -->
    <van-popup
      v-model:show="showNoticePopup"
      position="bottom"
      round
      closeable
      close-icon-position="top-left"
      :close-on-click-overlay="false"
      :style="{ maxHeight: '80%' }"
    >
      <div v-if="noticeDetail" class="notice-detail">
        <div class="notice-detail__title">{{ noticeDetail.title }}</div>
        <div
          v-if="getNoticeImage(noticeDetail)"
          class="notice-detail__image"
          @click="previewImage(getNoticeImage(noticeDetail))"
        >
          <img :src="getNoticeImage(noticeDetail)" :alt="noticeDetail.title" />
        </div>
        <div v-if="noticeDetail.content" class="notice-detail__content">{{ noticeDetail.content }}</div>
        <div class="notice-detail__footer safe-area-bottom">
          <van-button type="primary" round block @click="showNoticePopup = false">知道了</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.home {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  // 为固定 TabBar(64px + 安全区)留出空间
  padding-bottom: calc(64px + env(safe-area-inset-bottom));
  overflow: hidden;

  // ============ 顶部品牌标题模块(居中,固定不被滚动) ============
  &__brand-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 14px 16px 10px;
    text-align: center;
    flex-shrink: 0;
  }

  &__brand-logo {
    width: 56px;
    height: 56px;
    border-radius: 16px;
    background: rgba(0, 101, 253, 0.08);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    color: $brand-primary;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__brand-name {
    margin: 0;
    font-size: 19px;
    font-weight: 700;
    color: $brand-foreground;
    line-height: 1.3;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  // ============ 滚动通知 ticker ============
  &__ticker {
    display: flex;
    align-items: center;
    height: 40px;
    margin: 8px 16px 0;
    padding: 0 12px;
    gap: 8px;
    background: $brand-secondary;
    border-radius: 12px;
    overflow: hidden;
    cursor: pointer;
    flex-shrink: 0;
  }

  &__ticker-fixed {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 4px;
  }

  &__ticker-icon {
    color: $brand-primary;
  }

  &__ticker-label {
    font-size: 12px;
    font-weight: 500;
    color: $brand-primary;
  }

  &__ticker-viewport {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    position: relative;
    height: 20px;
    display: flex;
    align-items: center;
  }

  &__ticker-track {
    display: inline-flex;
    align-items: center;
    white-space: nowrap;
    will-change: transform;
    // 单条从右往左滚动一次,animationDuration 由内联样式按文本长度设置
    // animationend 事件触发切下一条(:key 变化重建元素,动画重新播放)
    animation: ticker-scroll-single var(--duration, 8s) linear forwards;
  }

  &__ticker-text {
    flex-shrink: 0;
    font-size: 14px;
    color: $brand-secondary-foreground;
    padding-right: 48px;
  }

  // ============ 内容区 ============
  &__content {
    padding: 20px 16px 0;
    flex: 1;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }

  // ============ 快捷入口 ============
  &__quick {
    margin-bottom: 24px;
  }

  &__quick-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }

  &__quick-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 12px 8px;
    background: transparent;
    border: none;
    cursor: pointer;
    transition: opacity 0.15s ease;

    &:active {
      opacity: 0.6;
    }
  }

  &__quick-icon {
    color: $brand-primary;
  }

  &__quick-text {
    font-size: 12px;
    color: $brand-foreground;
  }

  // ============ section 通用 ============
  &__section {
    margin-bottom: 24px;
  }

  &__section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  &__section-title {
    margin: 0;
    font-size: 16px;
    font-weight: 700;
    color: $brand-foreground;
  }

  &__section-more {
    font-size: 12px;
    color: $brand-muted-foreground;
    cursor: pointer;
  }

  // ============ 公司通知 ============
  &__notice-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  // ============ 公告/活动卡片(标题 + 图片 + 内容) ============
  &__announcement-card {
    padding: 16px;
    background: $brand-card;
    border: 1px solid $brand-border;
    border-radius: 16px;
    cursor: pointer;
    transition: opacity 0.15s ease;

    &:active {
      opacity: 0.6;
    }
  }

  &__announcement-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;
  }

  &__announcement-title {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.4;
    color: $brand-card-foreground;
  }

  &__announcement-image {
    margin-top: 8px;
    border-radius: 12px;
    overflow: hidden;
    background: $brand-muted;
    cursor: zoom-in;

    img {
      display: block;
      width: 100%;
      max-height: 200px;
      object-fit: cover;
    }
  }

  &__announcement-content {
    margin: 8px 0 0;
    font-size: 13px;
    line-height: 1.6;
    color: $brand-muted-foreground;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__notice-card {
    padding: 16px;
    background: $brand-card;
    border: 1px solid $brand-border;
    border-radius: 16px;
    cursor: pointer;
    transition: opacity 0.15s ease;

    &:active {
      opacity: 0.6;
    }
  }

  &__notice-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  &__notice-title {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: 14px;
    font-weight: 500;
    line-height: 1.4;
    color: $brand-card-foreground;
  }

  &__notice-tag {
    flex-shrink: 0;
    font-size: 10px;
    padding: 2px 8px;
    border-radius: 999px;
    white-space: nowrap;

    &--accent {
      background: $brand-accent;
      color: $brand-accent-foreground;
    }

    &--muted {
      background: $brand-muted;
      color: $brand-muted-foreground;
    }
  }

  &__notice-date {
    margin: 6px 0 0;
    font-size: 12px;
    color: $brand-muted-foreground;
  }

  // ============ CTA 去订餐 ============
  &__cta {
    width: 100%;
    height: 48px;
    margin-top: 8px;
    margin-bottom: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    background: $brand-primary;
    color: $brand-primary-fg;
    font-size: 16px;
    font-weight: 600;
    border: none;
    border-radius: 16px;
    cursor: pointer;
    transition: opacity 0.15s ease;

    &:active {
      opacity: 0.85;
    }
  }
}

/* ============ 通知详情弹层 ============ */
.notice-detail {
  display: flex;
  flex-direction: column;
  max-height: 80vh;

  &__title {
    text-align: center;
    font-size: 16px;
    font-weight: 700;
    padding: 14px 16px 12px;
    border-bottom: 1px solid $brand-border;
    color: $brand-foreground;
  }

  &__image {
    margin: 12px 16px 0;
    border-radius: 12px;
    overflow: hidden;
    background: $brand-muted;
    cursor: zoom-in;

    img {
      display: block;
      width: 100%;
      max-height: 40vh;
      object-fit: cover;
    }
  }

  &__content {
    flex: 1;
    overflow-y: auto;
    padding: 12px 16px;
    font-size: 14px;
    line-height: 1.7;
    color: $brand-foreground;
    white-space: pre-wrap;
    word-break: break-word;
    -webkit-overflow-scrolling: touch;
  }

  &__footer {
    padding: 10px 16px;
    border-top: 1px solid $brand-border;
    background: $brand-card;
  }
}

@keyframes ticker-scroll-single {
  0% {
    transform: translateX(var(--vp-w, 100%));
  }
  100% {
    transform: translateX(-100%);
  }
}
</style>
