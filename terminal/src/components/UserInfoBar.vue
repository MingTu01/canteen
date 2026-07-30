<script setup lang="ts">
/**
 * 用户信息条
 * - 头像(图片优先,失败回退首字母)+ 姓名 + 部门 + 余额
 * - 用于订餐流程的菜单/选菜/确认页顶部
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { User } from 'lucide-vue-next'
import { formatMoney } from '@/composables/useFormat'
import { getCachedAvatar } from '@/utils/imageCache'
import { loadConfig } from '@/api'

interface Emp {
  name?: string
  departmentName?: string
  balance?: number | string
  avatar?: string
}
const props = defineProps<{ employee: Emp | null }>()

const initial = computed(() => (props.employee?.name || '?').charAt(0))
const balanceText = computed(() => formatMoney(props.employee?.balance ?? 0))
const dept = computed(() => props.employee?.departmentName || '未分配部门')

/** 头像加载失败标志 */
const avatarError = ref(false)
/** 经过本地缓存处理的头像 URL */
const avatarSrc = ref('')
/** ObjectURL 生命周期管理（组件卸载时 revoke，避免内存泄漏） */
let currentObjectUrl = ''

const revokeOld = () => {
  if (currentObjectUrl && currentObjectUrl.startsWith('blob:')) {
    URL.revokeObjectURL(currentObjectUrl)
  }
  currentObjectUrl = ''
}

watch(
  () => props.employee?.avatar,
  async (raw) => {
    avatarError.value = false
    revokeOld()
    if (!raw) {
      avatarSrc.value = ''
      return
    }
    const config = loadConfig()
    const baseUrl = config?.serverUrl || ''
    const url = await getCachedAvatar(raw, baseUrl)
    // 若返回 blob: URL，登记以便后续 revoke
    if (url.startsWith('blob:')) {
      currentObjectUrl = url
    }
    avatarSrc.value = url
  },
  { immediate: true },
)

onBeforeUnmount(revokeOld)
</script>

<template>
  <div class="user-bar">
    <div class="user-bar__avatar">
      <img
        v-if="avatarSrc && !avatarError"
        :src="avatarSrc"
        :alt="employee?.name"
        class="user-bar__avatar-img"
        @error="avatarError = true"
      />
      <span v-else-if="employee?.name">{{ initial }}</span>
      <User v-else :size="22" />
    </div>
    <div class="user-bar__info">
      <span class="user-bar__name">{{ employee?.name || '未知员工' }}</span>
      <span class="user-bar__sep">|</span>
      <span class="user-bar__dept">{{ dept }}</span>
      <span class="user-bar__sep">|</span>
      <span class="user-bar__balance">余额 ¥{{ balanceText }}</span>
    </div>
  </div>
</template>

<style scoped>
.user-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 16px 24px;
  flex-shrink: 0;
  /* 透明背景,继承父级(OrderMenu)的深色背景 */
  background: transparent;
}
.user-bar__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--doubao-primary);
  color: var(--doubao-primary-foreground);
  font-size: var(--fs-base);
  font-weight: 700;
  flex-shrink: 0;
  border: 1.5px solid rgba(255, 255, 255, 0.2);
  overflow: hidden;
}
.user-bar__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.user-bar__info {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.user-bar__name {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: #ffffff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}
.user-bar__dept {
  font-size: var(--fs-base);
  color: rgba(255, 255, 255, 0.75);
}
.user-bar__balance {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--doubao-accent);
}
.user-bar__sep {
  color: rgba(255, 255, 255, 0.3);
  font-weight: 400;
}
</style>
