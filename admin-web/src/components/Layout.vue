<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import type { Component } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElDrawer, ElDropdown, ElDropdownMenu, ElDropdownItem, ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useAppStore } from '@/stores/app'
import { notificationApi, storeApi } from '@/api'
import type { Notification, Store } from '@/api'
import {
  Utensils,
  LayoutDashboard,
  ChefHat,
  BookOpen,
  ClipboardList,
  ClipboardCheck,
  CalendarCheck,
  Users,
  Building2,
  Clock,
  Megaphone,
  BarChart3,
  Wallet,
  Settings,
  DatabaseBackup,
  FileText,
  LogOut,
  Sun,
  Moon,
  Bell,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  ChevronRight,
  Home,
  Store as StoreIcon,
  UserCog,
  FileSpreadsheet,
  Truck,
  ShoppingCart,
  Package,
  MessageSquare,
} from 'lucide-vue-next'
import { normalizeList } from '@/utils/list'

interface MenuItem {
  path: string
  name: string
  icon: Component
  roles?: number[]
}

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const themeStore = useThemeStore()
const appStore = useAppStore()

const mobileDrawerOpen = ref(false)

const menuItems: MenuItem[] = [
  // 角色定义:1=超管,2=门店管理员,3=终端,4=财务,5=厨师长,6=店长
  { path: '/dashboard', name: '数据总览', icon: LayoutDashboard, roles: [1, 2, 4, 5, 6] },
  { path: '/dish', name: '菜品管理', icon: ChefHat, roles: [1, 2, 5, 6] },
  { path: '/menu', name: '菜单管理', icon: BookOpen, roles: [1, 2, 5, 6] },
  { path: '/order', name: '订单管理', icon: ClipboardList, roles: [1, 2, 6] },
  { path: '/order-summary', name: '订餐汇总', icon: FileSpreadsheet, roles: [1, 2, 5, 6] },
  { path: '/employee', name: '员工管理', icon: Users, roles: [1, 2, 6] },
  { path: '/department', name: '部门管理', icon: Building2, roles: [1, 2, 6] },
  { path: '/timer', name: '就餐时段', icon: Clock, roles: [1, 2, 6] },
  { path: '/notification', name: '通知管理', icon: Megaphone, roles: [1, 2, 6] },
  { path: '/supplier', name: '供应商管理', icon: Truck, roles: [1, 2, 5, 6] },
  { path: '/purchase', name: '采购管理', icon: ShoppingCart, roles: [1, 2, 5, 6] },
  { path: '/material', name: '库存食材', icon: Package, roles: [1, 2, 5, 6] },
  { path: '/feedback', name: '反馈评价', icon: MessageSquare, roles: [1, 2, 6] },
  { path: '/group-order', name: '团体订餐', icon: Users, roles: [1, 2, 6] },
  { path: '/report', name: '报表统计', icon: BarChart3, roles: [1, 2, 4, 6] },
  { path: '/daily-close', name: '日终对账', icon: ClipboardCheck, roles: [1, 2, 4, 6] },
  { path: '/settlement', name: '关店对账', icon: CalendarCheck, roles: [1, 2, 4, 6] },
  { path: '/recharge', name: '充值记录', icon: Wallet, roles: [1, 2, 4, 6] },
  { path: '/store', name: '食堂管理', icon: StoreIcon, roles: [1] },
  { path: '/admin', name: '账号管理', icon: UserCog, roles: [1, 2] },
  { path: '/settings', name: '系统设置', icon: Settings, roles: [1] },
  { path: '/backup', name: '备份恢复', icon: DatabaseBackup, roles: [1, 2] },
  { path: '/operation-log', name: '操作日志', icon: FileText, roles: [1, 2] },
]

const collapsed = computed(() => appStore.sidebarCollapsed)
const visibleMenuItems = computed(() =>
  menuItems.filter((item) => !item.roles || authStore.hasRole(...item.roles))
)
const currentTitle = computed(() => route.meta.title || '数据总览')

const isActive = (path: string) => route.path === path

/* ===== 当前管理食堂标识 ===== */
const currentStore = ref<Store | null>(null)

const fetchCurrentStore = async () => {
  // 超管 storeId=0 表示全局视图;门店管理员 storeId=具体食堂
  const sid = authStore.storeId
  if (!sid || sid === 0) {
    currentStore.value = null
    return
  }
  try {
    currentStore.value = await storeApi.getCurrent()
  } catch {
    currentStore.value = null
  }
}

// storeId 变化时(切换食堂)重新拉取
watch(() => authStore.storeId, fetchCurrentStore)

const handleCommand = async (command: string | number | object) => {
  if (command === 'logout') {
    await authStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}

watch(
  () => route.path,
  () => {
    mobileDrawerOpen.value = false
  }
)

/* ===== 通知铃铛:未读/进行中通知计数 ===== */
const unreadCount = ref(0)
const notifications = ref<Notification[]>([])

const fetchUnreadCount = async () => {
  const sid = authStore.storeId
  if (!sid) {
    unreadCount.value = 0
    return
  }
  try {
    const raw = await notificationApi.list({ storeId: sid })
    notifications.value = normalizeList<Notification>(raw)
    // 进行中(active)且启用的通知数量作为未读计数
    unreadCount.value = notifications.value.filter(
      (n) => n.status === 1 && n.displayStatus === 'active'
    ).length
  } catch {
    unreadCount.value = 0
  }
}

// 路由变化时刷新通知计数(进入/离开通知页时同步)
watch(
  () => route.path,
  () => {
    fetchUnreadCount()
  }
)

let notifyTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  fetchCurrentStore()
  fetchUnreadCount()
  // 每 60 秒刷新一次通知计数
  notifyTimer = setInterval(fetchUnreadCount, 60_000)
})

onBeforeUnmount(() => {
  if (notifyTimer) clearInterval(notifyTimer)
  notifyTimer = null
})
</script>

<template>
  <div class="flex min-h-screen bg-bg-secondary">
    <!-- Desktop Sidebar -->
    <aside
      class="hidden lg:flex flex-col border-r border-border bg-card transition-all duration-300"
      :class="collapsed ? 'w-16' : 'w-60'"
    >
      <!-- Logo -->
      <div class="flex h-16 items-center gap-2.5 border-b border-border px-4">
        <img
          v-if="currentStore?.logoUrl"
          :src="currentStore.logoUrl"
          :alt="currentStore.name"
          class="h-9 w-9 shrink-0 rounded-xl object-cover"
        />
        <div v-else class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary">
          <Utensils class="h-5 w-5 text-white" />
        </div>
        <span v-if="!collapsed" class="truncate text-base font-bold text-text">
          {{ currentStore?.name || '企业智慧食堂' }}
        </span>
      </div>

      <!-- 当前食堂标识(超管切换后显示) -->
      <div v-if="!collapsed && authStore.isSuperAdmin" class="border-b border-border px-4 py-2">
        <div class="flex items-center gap-1.5">
          <StoreIcon class="h-3.5 w-3.5 shrink-0 text-text-muted" />
          <span class="truncate text-xs text-text-muted">
            {{ currentStore ? currentStore.name : '全局视图' }}
          </span>
          <router-link to="/store" class="ml-auto shrink-0 text-xs text-primary hover:underline">
            切换
          </router-link>
        </div>
      </div>

      <!-- Nav -->
      <nav class="flex-1 overflow-y-auto px-2 py-3" role="navigation" aria-label="主导航">
        <router-link
          v-for="item in visibleMenuItems"
          :key="item.path"
          :to="item.path"
          :title="collapsed ? item.name : ''"
          :aria-current="isActive(item.path) ? 'page' : undefined"
          class="group mb-1 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-all"
          :class="[
            isActive(item.path)
              ? 'bg-primary-50 font-medium text-primary'
              : 'text-text-secondary hover:bg-bg-tertiary hover:text-text',
            collapsed ? 'justify-center' : '',
          ]"
        >
          <component :is="item.icon" class="h-5 w-5 shrink-0" />
          <span v-if="!collapsed" class="truncate">{{ item.name }}</span>
        </router-link>
      </nav>

      <!-- Collapse toggle -->
      <div class="border-t border-border p-2">
        <button
          class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-text-secondary transition-colors hover:bg-bg-tertiary"
          :class="collapsed ? 'justify-center' : ''"
          :aria-label="collapsed ? '展开菜单' : '折叠菜单'"
          :aria-expanded="!collapsed"
          @click="appStore.toggleSidebar()"
        >
          <component :is="collapsed ? PanelLeftOpen : PanelLeftClose" class="h-5 w-5 shrink-0" />
          <span v-if="!collapsed">收起菜单</span>
        </button>
      </div>
    </aside>

    <!-- Mobile Drawer -->
    <ElDrawer v-model="mobileDrawerOpen" direction="ltr" size="260px" :with-header="false">
      <div class="flex h-full flex-col bg-card">
        <div class="flex h-16 items-center gap-2.5 border-b border-border px-4">
          <img
            v-if="currentStore?.logoUrl"
            :src="currentStore.logoUrl"
            :alt="currentStore.name"
            class="h-9 w-9 shrink-0 rounded-xl object-cover"
          />
          <div v-else class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary">
            <Utensils class="h-5 w-5 text-white" />
          </div>
          <span class="truncate text-base font-bold text-text">
            {{ currentStore?.name || '企业智慧食堂' }}
          </span>
        </div>
        <!-- 当前食堂标识(超管切换后显示) -->
        <div v-if="authStore.isSuperAdmin" class="border-b border-border px-4 py-2">
          <div class="flex items-center gap-1.5">
            <StoreIcon class="h-3.5 w-3.5 shrink-0 text-text-muted" />
            <span class="truncate text-xs text-text-muted">
              {{ currentStore ? currentStore.name : '全局视图' }}
            </span>
            <router-link to="/store" class="ml-auto shrink-0 text-xs text-primary hover:underline">
              切换
            </router-link>
          </div>
        </div>
        <nav class="flex-1 overflow-y-auto px-2 py-3" role="navigation" aria-label="主导航">
          <router-link
            v-for="item in visibleMenuItems"
            :key="item.path"
            :to="item.path"
            :aria-current="isActive(item.path) ? 'page' : undefined"
            class="mb-1 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-all"
            :class="
              isActive(item.path)
                ? 'bg-primary-50 font-medium text-primary'
                : 'text-text-secondary hover:bg-bg-tertiary'
            "
            @click="mobileDrawerOpen = false"
          >
            <component :is="item.icon" class="h-5 w-5 shrink-0" />
            <span class="truncate">{{ item.name }}</span>
          </router-link>
        </nav>
      </div>
    </ElDrawer>

    <!-- Main Content -->
    <div class="flex flex-1 flex-col overflow-hidden">
      <!-- Header -->
      <header
        class="flex h-16 shrink-0 items-center justify-between border-b border-border bg-card px-4 lg:px-6"
      >
        <div class="flex items-center gap-3">
          <button
            class="rounded-lg p-2 hover:bg-bg-tertiary lg:hidden"
            aria-label="打开菜单"
            @click="mobileDrawerOpen = true"
          >
            <Menu class="h-5 w-5 text-text-secondary" />
          </button>
          <!-- Breadcrumb -->
          <div class="flex items-center gap-1.5 text-sm">
            <router-link to="/dashboard" class="flex items-center text-text-muted hover:text-primary">
              <Home class="h-4 w-4" />
            </router-link>
            <ChevronRight class="h-4 w-4 text-text-muted" />
            <span class="font-medium text-text">{{ currentTitle }}</span>
          </div>
        </div>

        <div class="flex items-center gap-1">
          <!-- Theme toggle -->
          <button
            class="rounded-lg p-2 hover:bg-bg-tertiary"
            :aria-label="themeStore.isDark ? '切换到亮色主题' : '切换到暗色主题'"
            @click="themeStore.toggle()"
          >
            <Sun v-if="themeStore.isDark" class="h-5 w-5 text-warning" />
            <Moon v-else class="h-5 w-5 text-text-secondary" />
          </button>

          <!-- Notification -->
          <button
            class="relative rounded-lg p-2 hover:bg-bg-tertiary"
            title="通知管理"
            aria-label="通知"
            @click="router.push('/notification')"
          >
            <Bell class="h-5 w-5 text-text-secondary" />
            <span
              v-if="unreadCount > 0"
              class="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white ring-2 ring-card"
              aria-live="polite"
            >
              {{ unreadCount > 99 ? '99+' : unreadCount }}
            </span>
            <span
              v-else
              class="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-danger ring-2 ring-card"
              aria-live="polite"
            />
          </button>

          <!-- User dropdown -->
          <ElDropdown trigger="click" @command="handleCommand">
            <div
              class="flex cursor-pointer items-center gap-2 rounded-lg p-1.5 hover:bg-bg-tertiary"
              aria-label="用户菜单"
              role="button"
              tabindex="0"
            >
              <div
                class="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-semibold text-white"
              >
                {{ authStore.admin?.name?.charAt(0) || '管' }}
              </div>
              <div class="hidden sm:block">
                <div class="text-sm font-medium text-text">
                  {{ authStore.admin?.name || '管理员' }}
                </div>
                <div class="text-xs text-text-muted">{{ authStore.admin?.username }}</div>
              </div>
            </div>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem command="logout">
                  <span class="flex items-center gap-2">
                    <LogOut class="h-4 w-4" />
                    退出登录
                  </span>
                </ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </div>
      </header>

      <!-- Main -->
      <main class="flex-1 overflow-y-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
