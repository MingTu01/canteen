<script setup lang="ts">
/**
 * 用户信息条
 * - 头像(首字母)+ 姓名 + 部门 + 余额
 * - 用于订餐流程的菜单/选菜/确认页顶部
 */
import { computed } from 'vue'
import { User } from 'lucide-vue-next'
import { formatMoney } from '@/composables/useFormat'

interface Emp {
  name?: string
  departmentName?: string
  balance?: number | string
}
const props = defineProps<{ employee: Emp | null }>()

const initial = computed(() => (props.employee?.name || '?').charAt(0))
const balanceText = computed(() => formatMoney(props.employee?.balance ?? 0))
const dept = computed(() => props.employee?.departmentName || '未分配部门')
</script>

<template>
  <div class="user-bar">
    <div class="user-bar__avatar">
      <span v-if="employee?.name">{{ initial }}</span>
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
  font-weight: 600;
  flex-shrink: 0;
  border: 1.5px solid rgba(255, 255, 255, 0.2);
}
.user-bar__info {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.user-bar__name {
  font-size: var(--fs-lg);
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}
.user-bar__dept {
  font-size: var(--fs-base);
  color: rgba(255, 255, 255, 0.75);
}
.user-bar__balance {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--doubao-accent);
}
.user-bar__sep {
  color: rgba(255, 255, 255, 0.3);
  font-weight: 400;
}
</style>
