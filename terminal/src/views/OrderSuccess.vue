<script setup lang="ts">
/**
 * 下单成功页
 *
 * 下单成功后展示:
 * - 成功图标(主色)
 * - 金额 + 餐别 + 日期
 * - 返回按钮
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { orderStore } from '@/store/order'
import { formatMoney } from '@/composables/useFormat'
import { CheckCircle2 } from 'lucide-vue-next'
import BigButton from '@/components/BigButton.vue'

const router = useRouter()
const last = computed(() => orderStore.lastOrder)

onMounted(() => {
  if (!orderStore.employee) router.replace('/order')
  if (!last.value) router.replace('/order/menu')
})

const goMenu = () => router.push('/order/menu')
</script>

<template>
  <main class="success">
    <div class="success__inner">
      <div class="success__icon">
        <CheckCircle2 :size="56" />
      </div>
      <p class="success__title">订餐成功</p>

      <div v-if="last" class="success__card">
        <span class="success__meta">{{ last.mealLabel || '订餐' }} · {{ last.dateLabel }}</span>
        <p class="success__amount">¥{{ formatMoney(last.total) }}</p>
      </div>

      <BigButton variant="outline" size="lg" @click="goMenu">返回</BigButton>
    </div>
  </main>
</template>

<style scoped>
.success {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px 24px;
}
.success__inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  width: 100%;
  max-width: 720px;
}
.success__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--doubao-muted);
  color: var(--doubao-primary);
}
.success__title {
  margin: 0;
  font-size: var(--fs-2xl);
  font-weight: 700;
  color: var(--doubao-primary);
}
.success__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 40px;
  background: var(--doubao-accent);
  border-radius: var(--doubao-radius);
}
.success__meta {
  font-size: var(--fs-base);
  color: var(--doubao-muted-foreground);
}
.success__amount {
  margin: 0;
  font-size: var(--fs-3xl);
  font-weight: 700;
  color: var(--doubao-primary);
}
</style>
