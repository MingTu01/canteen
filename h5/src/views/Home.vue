<script setup lang="ts">
import { ref, computed, watch, onMounted, onActivated } from 'vue'

import { useRouter } from 'vue-router'
import { showToast, showDialog, showImagePreview } from 'vant'
import {
  Megaphone,
  UtensilsCrossed,
  ClipboardList,
  MessageSquare,
  Soup,
  Salad,
  IceCreamCone,
  CupSoda,
  Croissant,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useBrandingStore } from '@/stores/branding'
import { getStoreNotifications } from '@/api/notification'
import { getNewDishes } from '@/api/menu'
import { formatMoney, formatDate } from '@/composables/useFormat'
import { getCachedImage } from '@/utils/imageCache'
import type { Notification, Dish } from '@/api/types'

defineOptions({ name: 'Home' })

const router = useRouter()
const authStore = useAuthStore()
const brandingStore = useBrandingStore()

/** 品牌信息(logo / 名称 / 描述) */
const branding = computed(() => brandingStore.branding)

/** 通知列表 */
const notifications = ref<Notification[]>([])

/** 今日新品 */
const newDishes = ref<Dish[]>([])

/** 加载状态 */
const notificationLoading = ref(true)
const dishesLoading = ref(true)

/** 加载失败的菜品图片(dish.id),触发 v-if 回退到 Lucide 占位图标 */
const erroredDishImages = ref<Set<number>>(new Set())

/** 标记首次挂载,避免 onMounted + onActivated 双重触发重复请求 */
let firstMount = true

/** 通知公告滚动文案(将所有标题用 · 拼接) */
const noticeText = computed(() => {
  if (notifications.value.length === 0) return ''
  return notifications.value.map((n) => n.title).join('  ·  ')
})

/** 公告列表(type=3):展示标题 + 图片 + 内容 */
const announcementList = computed(() =>
  notifications.value.filter((n) => n.type === 3).slice(0, 10),
)

/** 活动列表(type=4):列表仅展示标题,点击展开详情 */
const activityList = computed(() =>
  notifications.value.filter((n) => n.type === 4).slice(0, 10),
)

/** 其他通知(非公告非活动):保留在原"公司通知"列表 */
const noticeList = computed(() =>
  notifications.value.filter((n) => n.type !== 3 && n.type !== 4).slice(0, 5),
)

/** 活动详情弹窗 */
const activityDetail = ref<Notification | null>(null)
const showActivityPopup = ref(false)

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

/** 展示活动详情 */
const showActivityDetail = (item: Notification): void => {
  activityDetail.value = item
  showActivityPopup.value = true
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
    case 2:
      return { label: '新品推荐', variant: 'accent' }
    case 3:
      return { label: '公告', variant: 'muted' }
    default:
      return { label: '公告', variant: 'muted' }
  }
}

/** 菜品占位图标:根据品类关键词选择 Lucide 图标,无品类时回退通用餐具图标 */
const categoryIcon = (dish: Dish) => {
  const cat = (dish.category || '').toLowerCase()
  if (cat.includes('汤') || cat.includes('soup')) return Soup
  if (cat.includes('凉') || cat.includes('拌') || cat.includes('salad')) return Salad
  if (cat.includes('冰') || cat.includes('甜') || cat.includes('dessert')) return IceCreamCone
  if (cat.includes('饮') || cat.includes('茶') || cat.includes('drink')) return CupSoda
  if (cat.includes('面') || cat.includes('包') || cat.includes('糕') || cat.includes('bread')) {
    return Croissant
  }
  return UtensilsCrossed
}

/** 跳转 */
const go = (path: string): void => {
  router.push(path)
}

/** 通知公告条点击:用 dialog 展示完整内容,避免 toast 截断长文本 */
const onNoticeBarClick = (): void => {
  if (notifications.value.length === 0) return
  const first = notifications.value[0]
  showDialog({
    title: first.title,
    message: first.content || first.title,
    confirmButtonText: '知道了',
  }).catch(() => {
    /* ignore */
  })
}

/** 通知列表项点击:用 dialog 展示完整内容 */
const onNoticeItemClick = (item: Notification): void => {
  showDialog({
    title: item.title,
    message: item.content || item.title,
    confirmButtonText: '知道了',
  }).catch(() => {
    /* ignore */
  })
}

/** 菜品图片加载失败:标记该菜品,触发 v-if 切换到 Lucide 占位图标 */
const onDishImgError = (dish: Dish): void => {
  if (erroredDishImages.value.has(dish.id)) return
  const next = new Set(erroredDishImages.value)
  next.add(dish.id)
  erroredDishImages.value = next
}

/** 菜品图片缓存 Map（dish.id → 缓存后的 blob URL 或原 URL），异步填充 */
const cachedDishImages = ref<Map<number, string>>(new Map())

/** 批量预加载菜品图片到缓存 Map（懒加载：首次 fetch 后缓存，后续命中缓存直接返回 blob URL） */
const refreshDishImages = async (): Promise<void> => {
  const map = new Map<number, string>()
  await Promise.all(
    newDishes.value.map(async (dish) => {
      const raw = dish.image || dish.imageUrl
      if (!raw) return
      // 完整 URL 或 data URL 直接使用，不走 IndexedDB 缓存
      if (/^(https?:)?\/\//.test(raw) || raw.startsWith('data:')) {
        map.set(dish.id, raw)
        return
      }
      map.set(dish.id, await getCachedImage(raw))
    }),
  )
  cachedDishImages.value = map
}

/** 菜品图片地址(优先返回缓存 blob URL);已失败的返回空串触发占位图标 */
const getDishImage = (dish: Dish): string => {
  if (erroredDishImages.value.has(dish.id)) return ''
  return cachedDishImages.value.get(dish.id) || ''
}

// 菜品列表变化时异步刷新缓存 Map
watch(newDishes, refreshDishImages, { immediate: true })

// 通知列表变化时异步刷新图片缓存 Map
watch(notifications, refreshNoticeImages, { immediate: true })

/** 点击新品卡片 */
const onDishCardClick = (): void => {
  showToast('请前往订餐页下单')
}

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

/** 拉取今日新品 */
const loadNewDishes = async (storeId: number): Promise<void> => {
  dishesLoading.value = true
  try {
    const res = await getNewDishes(storeId, { page: 1, size: 10 })
    newDishes.value = res?.records ?? []
  } catch {
    /* 拦截器已 toast */
  } finally {
    dishesLoading.value = false
  }
}

onMounted(async () => {
  const storeId = authStore.storeId
  if (!storeId) return

  // 并行拉取:品牌信息(秒开缓存)、通知、新品
  await Promise.all([
    brandingStore.fetchBranding(storeId),
    loadNotifications(storeId),
    loadNewDishes(storeId),
  ])
})

// keep-alive 重新激活时刷新通知与新品,避免展示过期数据
onActivated(() => {
  // keep-alive 首次挂载时 onMounted + onActivated 均触发,跳过首次避免重复请求
  if (firstMount) {
    firstMount = false
    return
  }
  const storeId = authStore.storeId
  if (!storeId) return
  loadNotifications(storeId)
  loadNewDishes(storeId)
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

    <!-- 滚动通知栏(ticker,横向无限滚动,替代 van-notice-bar) -->
    <div v-if="notifications.length > 0" class="home__ticker" @click="onNoticeBarClick">
      <div class="home__ticker-fixed">
        <Megaphone :size="16" :stroke-width="2" class="home__ticker-icon" />
        <span class="home__ticker-label">通知</span>
      </div>
      <div class="home__ticker-viewport">
        <div class="home__ticker-track">
          <span class="home__ticker-text">{{ noticeText }}</span>
          <span class="home__ticker-text">{{ noticeText }}</span>
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

      <!-- 今日新品(无新品时隐藏整个模块) -->
      <section v-if="dishesLoading || newDishes.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">今日新品</h2>
          <span class="home__section-more" @click="go('/order')">查看更多</span>
        </div>

        <!-- 加载骨架 -->
        <van-skeleton v-if="dishesLoading" :row="3" :loading="true" title>
          <template #template>
            <div class="home__dish-list">
              <van-skeleton-image v-for="i in 3" :key="i" image-size="80" />
            </div>
          </template>
        </van-skeleton>

        <!-- 横向滑动卡片列表 -->
        <div v-else class="home__dish-list">
          <div
            v-for="dish in newDishes"
            :key="dish.id"
            class="home__dish-card"
            @click="onDishCardClick"
          >
            <div class="home__dish-image">
              <img
                v-if="getDishImage(dish)"
                :src="getDishImage(dish)"
                :alt="dish.name"
                loading="lazy"
                @error="onDishImgError(dish)"
              />
              <component
                v-else
                :is="categoryIcon(dish)"
                :size="28"
                :stroke-width="2"
                class="home__dish-placeholder"
              />
            </div>
            <div class="home__dish-name">{{ dish.name }}</div>
            <div class="home__dish-footer">
              <span class="home__dish-price">¥{{ formatMoney(dish.price) }}</span>
              <span class="home__dish-pill">新品</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 公告(展示标题 + 图片 + 内容,与新品展示方式一致) -->
      <section v-if="announcementList.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">公告</h2>
        </div>
        <div class="home__notice-list">
          <div
            v-for="item in announcementList"
            :key="item.id"
            class="home__announcement-card"
          >
            <h3 class="home__announcement-title">{{ item.title }}</h3>
            <div
              v-if="getNoticeImage(item)"
              class="home__announcement-image"
              @click="previewImage(getNoticeImage(item))"
            >
              <img :src="getNoticeImage(item)" :alt="item.title" loading="lazy" />
            </div>
            <p v-if="item.content" class="home__announcement-content">{{ item.content }}</p>
          </div>
        </div>
      </section>

      <!-- 活动(列表仅展示标题,点击展开标题 + 图片 + 内容) -->
      <section v-if="activityList.length > 0" class="home__section">
        <div class="home__section-header">
          <h2 class="home__section-title">活动</h2>
        </div>
        <div class="home__notice-list">
          <div
            v-for="item in activityList"
            :key="item.id"
            class="home__notice-card"
            @click="showActivityDetail(item)"
          >
            <div class="home__notice-top">
              <h3 class="home__notice-title">{{ item.title }}</h3>
              <span class="home__notice-tag home__notice-tag--accent">活动</span>
            </div>
            <p class="home__notice-date">{{ formatDate(item.createdAt || item.startDate) }}</p>
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
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.home {
  min-height: 100vh;
  // 为固定 TabBar(64px + 安全区)留出空间
  padding-bottom: calc(64px + constant(safe-area-inset-bottom));
  padding-bottom: calc(64px + env(safe-area-inset-bottom));

  // ============ 顶部品牌标题模块(居中) ============
  &__brand-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 14px 16px 10px;
    text-align: center;
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
  }

  &__ticker-track {
    display: inline-flex;
    align-items: center;
    white-space: nowrap;
    animation: ticker-scroll 16s linear infinite;

    &:hover {
      animation-play-state: paused;
    }
  }

  &__ticker-text {
    flex-shrink: 0;
    font-size: 14px;
    color: $brand-secondary-foreground;
    // 用 padding-right 而非 gap 提供间隔,保证 -50% 平移无缝衔接
    padding-right: 48px;
  }

  // ============ 内容区 ============
  &__content {
    padding: 20px 16px 0;
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

  // ============ 今日新品 ============
  &__dish-list {
    display: flex;
    gap: 12px;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 4px;
    -webkit-overflow-scrolling: touch;

    &::-webkit-scrollbar {
      display: none;
    }
  }

  &__dish-card {
    flex-shrink: 0;
    width: 130px;
    padding: 12px;
    background: $brand-card;
    border: 1px solid $brand-border;
    border-radius: 16px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    transition: opacity 0.15s ease;

    &:active {
      opacity: 0.6;
    }
  }

  &__dish-image {
    width: 80px;
    height: 80px;
    border-radius: 12px;
    background: $brand-muted;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__dish-placeholder {
    color: $brand-muted-foreground;
  }

  &__dish-name {
    max-width: 100%;
    font-size: 14px;
    font-weight: 500;
    color: $brand-card-foreground;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__dish-footer {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__dish-price {
    font-size: 14px;
    font-weight: 700;
    color: $brand-primary;
  }

  &__dish-pill {
    font-size: 10px;
    font-weight: 500;
    padding: 2px 6px;
    border-radius: 999px;
    background: $brand-accent;
    color: $brand-accent-foreground;
  }

  // ============ 公司通知 ============
  &__notice-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
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

@keyframes ticker-scroll {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(-50%);
  }
}
</style>
