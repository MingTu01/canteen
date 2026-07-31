<script setup lang="ts">
/**
 * 报表统计(主页面)
 *
 * 职责:门店切换 + Tab 容器 + 转发 activeStoreId 给子组件
 * 各 Tab 子组件自行管理数据加载、图表渲染、Excel 导出
 *
 * 子组件挂载时 watch storeId immediate 自动 fetch;
 * 门店切换时已挂载的子组件自动重新 fetch;
 * 父组件 "刷新" 按钮调用当前活跃 Tab 的 refresh 方法(手动重试)
 */
import { ref, onMounted } from 'vue'
import { ElButton, ElSelect, ElOption, ElTabs, ElTabPane } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import { useStoreSwitch } from '@/composables/useStoreSwitch'
import BusinessReport from './components/BusinessReport.vue'
import FinanceReport from './components/FinanceReport.vue'
import EmployeeConsumption from './components/EmployeeConsumption.vue'
import YoyReport from './components/YoyReport.vue'
import MomReport from './components/MomReport.vue'
import CongestionReport from './components/CongestionReport.vue'

/* 门店选择(超管)— 复用 composable */
const { isSuperAdmin, stores, selectedStoreId, activeStoreId, fetchStores } = useStoreSwitch()

/* Tab 切换 */
type TabName = 'business' | 'finance' | 'employee' | 'yoy' | 'mom' | 'congestion'
const activeTab = ref<TabName>('business')

/* 页面加载状态(超管首次拉取门店列表时) */
const pageLoading = ref(false)

/* 子组件引用(用于调用 refresh 方法) */
const businessRef = ref<InstanceType<typeof BusinessReport> | null>(null)
const financeRef = ref<InstanceType<typeof FinanceReport> | null>(null)
const empRef = ref<InstanceType<typeof EmployeeConsumption> | null>(null)
const yoyRef = ref<InstanceType<typeof YoyReport> | null>(null)
const momRef = ref<InstanceType<typeof MomReport> | null>(null)
const congestionRef = ref<InstanceType<typeof CongestionReport> | null>(null)

/** 刷新当前活跃 Tab */
const refreshActiveTab = (): void => {
  switch (activeTab.value) {
    case 'business':
      businessRef.value?.refresh()
      break
    case 'finance':
      financeRef.value?.refresh()
      break
    case 'employee':
      empRef.value?.refresh()
      break
    case 'yoy':
      yoyRef.value?.refresh()
      break
    case 'mom':
      momRef.value?.refresh()
      break
    case 'congestion':
      congestionRef.value?.refresh()
      break
  }
}

/* 挂载时拉取门店列表(子组件 watch storeId 会自动 fetch) */
onMounted(async () => {
  if (isSuperAdmin.value) {
    pageLoading.value = true
    try {
      await fetchStores()
    } finally {
      pageLoading.value = false
    }
  }
})
</script>

<template>
  <Layout>
    <PageContainer title="报表统计" description="多维度营业数据可视化分析,支持日报/周报/月报与 Excel 导出。">
      <template #actions>
        <ElButton :icon="RefreshCw" @click="refreshActiveTab">刷新</ElButton>
      </template>

      <div
        v-if="!activeStoreId && !pageLoading"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <!-- 门店选择(共享) -->
      <div
        v-if="isSuperAdmin"
        class="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-sm"
      >
        <span class="text-sm text-text-muted">选择门店</span>
        <ElSelect
          v-model="selectedStoreId"
          placeholder="选择门店"
          style="width: 220px"
          :loading="pageLoading"
        >
          <ElOption
            v-for="s in stores"
            :key="s.id"
            :label="s.name"
            :value="s.id as number"
          />
        </ElSelect>
      </div>
      <div v-else class="mb-5 flex items-center">
        <span class="text-sm text-text-muted">当前门店</span>
      </div>

      <ElTabs v-model="activeTab">
        <ElTabPane label="营业报表" name="business">
          <BusinessReport ref="businessRef" :store-id="activeStoreId" />
        </ElTabPane>
        <ElTabPane label="财务对账" name="finance" lazy>
          <FinanceReport ref="financeRef" :store-id="activeStoreId" />
        </ElTabPane>
        <ElTabPane label="员工消费统计" name="employee" lazy>
          <EmployeeConsumption ref="empRef" :store-id="activeStoreId" />
        </ElTabPane>
        <ElTabPane label="同比分析" name="yoy" lazy>
          <YoyReport ref="yoyRef" :store-id="activeStoreId" />
        </ElTabPane>
        <ElTabPane label="环比分析" name="mom" lazy>
          <MomReport ref="momRef" :store-id="activeStoreId" />
        </ElTabPane>
        <ElTabPane label="拥堵分析" name="congestion" lazy>
          <CongestionReport ref="congestionRef" :store-id="activeStoreId" />
        </ElTabPane>
      </ElTabs>
    </PageContainer>
  </Layout>
</template>
