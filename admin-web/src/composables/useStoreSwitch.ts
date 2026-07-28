/**
 * 超管门店切换 composable
 *
 * 拆分自 5 个视图文件(Report/Settlement/Backup/Settings/Store)中重复的:
 *   - isSuperAdmin / stores / selectedStoreId / activeStoreId / fetchStores
 *
 * 用法:
 *   const { isSuperAdmin, stores, selectedStoreId, activeStoreId, fetchStores }
 *     = useStoreSwitch()
 *
 * 注意:
 *   - 非超管时 selectedStoreId 保持 undefined,activeStoreId 回退到 authStore.storeId
 *   - fetchStores 内部判断 isSuperAdmin,非超管直接返回空数组
 *   - 超管首次调用会自动选第一个门店作为 selectedStoreId
 *   - 调用方负责在 onMounted 中显式调用 fetchStores,本 composable 不自动触发
 *     (避免与页面其他初始化逻辑的执行顺序耦合)
 */

import { ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { storeApi } from '@/api'
import type { Store } from '@/api'

export const useStoreSwitch = () => {
  const authStore = useAuthStore()

  const isSuperAdmin = computed(() => authStore.isSuperAdmin)

  const stores = ref<Store[]>([])

  /** 超管手动选中的门店;非超管保持 undefined(用 activeStoreId 回退到自身门店) */
  const selectedStoreId = ref<number | undefined>(authStore.storeId || undefined)

  /**
   * 当前生效的门店 ID:
   *   - 超管:优先使用 selectedStoreId;未选时回退到 authStore.storeId;仍无则 null
   *   - 非超管:authStore.storeId
   *   - 返回 null 表示"未选店",调用方据此展示"请先选择食堂"占位
   */
  const activeStoreId = computed(() => selectedStoreId.value || authStore.storeId || null)

  /** 拉取门店列表(仅超管);首次拉取后自动选中第一家作为默认值 */
  const fetchStores = async (): Promise<void> => {
    if (!isSuperAdmin.value) return
    try {
      stores.value = await storeApi.list()
      if (!selectedStoreId.value && stores.value.length) {
        selectedStoreId.value = stores.value[0].id
      }
    } catch {
      /* 错误已由 axios 拦截器统一提示 */
    }
  }

  return {
    isSuperAdmin,
    stores,
    selectedStoreId,
    activeStoreId,
    fetchStores,
  }
}
