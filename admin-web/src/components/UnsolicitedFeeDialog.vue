<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElDialog,
  ElButton,
  ElForm,
  ElFormItem,
  ElSwitch,
  ElInputNumber,
  ElMessage,
} from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { systemApi } from '@/api'

/**
 * 未订餐用餐手续费设置弹窗(按门店)。
 * 管理员可为每个餐别单独设置手续费,未订餐用餐订单下单时按餐别加收。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', val: boolean): void }>()

const authStore = useAuthStore()
// 超管未选择食堂时为 null
const storeId = computed(() => authStore.storeId || null)

const saving = ref(false)
const feeForm = ref({
  unsolicited_fee_enabled: false,
  unsolicited_fee_breakfast: 0,
  unsolicited_fee_lunch: 0,
  unsolicited_fee_dinner: 0,
})

/** 解析金额配置:非法/负数回退 0 */
const parseFee = (v: string | number | null | undefined) => {
  const n = Number(v)
  return v != null && v !== '' && !isNaN(n) && n > 0 ? n : 0
}

// 打开弹窗时读取门店配置回显("true"/"false" 转 boolean,金额转 number)
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    const sid = storeId.value
    // 超管未选门店时不允许打开
    if (!sid) {
      ElMessage.warning('请先选择食堂')
      emit('update:modelValue', false)
      return
    }
    try {
      const cfg = await systemApi.getOrderConfig(sid)
      feeForm.value = {
        unsolicited_fee_enabled:
          cfg.unsolicited_fee_enabled === true || cfg.unsolicited_fee_enabled === 'true',
        unsolicited_fee_breakfast: parseFee(cfg.unsolicited_fee_breakfast),
        unsolicited_fee_lunch: parseFee(cfg.unsolicited_fee_lunch),
        unsolicited_fee_dinner: parseFee(cfg.unsolicited_fee_dinner),
      }
    } catch {
      /* 拦截器提示 */
    }
  },
)

const handleSave = async () => {
  const sid = storeId.value
  if (!sid) {
    ElMessage.warning('请先选择食堂')
    return
  }
  saving.value = true
  try {
    await systemApi.updateOrderConfig(sid, [
      { key: 'unsolicited_fee_enabled', value: String(feeForm.value.unsolicited_fee_enabled) },
      { key: 'unsolicited_fee_breakfast', value: String(feeForm.value.unsolicited_fee_breakfast) },
      { key: 'unsolicited_fee_lunch', value: String(feeForm.value.unsolicited_fee_lunch) },
      { key: 'unsolicited_fee_dinner', value: String(feeForm.value.unsolicited_fee_dinner) },
    ])
    ElMessage.success('手续费设置已保存')
    emit('update:modelValue', false)
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="未订餐用餐手续费设置"
    width="560px"
    :close-on-click-modal="false"
    append-to-body
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <ElForm :model="feeForm" label-width="140px" label-position="right">
      <ElFormItem label="启用手续费">
        <ElSwitch v-model="feeForm.unsolicited_fee_enabled" />
        <span class="ml-3 text-xs text-text-muted">开启后未订餐用餐订单按餐别加收手续费</span>
      </ElFormItem>
      <ElFormItem label="早餐手续费（元）">
        <ElInputNumber
          v-model="feeForm.unsolicited_fee_breakfast"
          :min="0"
          :max="9999"
          :precision="2"
          :step="0.5"
        />
      </ElFormItem>
      <ElFormItem label="午餐手续费（元）">
        <ElInputNumber
          v-model="feeForm.unsolicited_fee_lunch"
          :min="0"
          :max="9999"
          :precision="2"
          :step="0.5"
        />
      </ElFormItem>
      <ElFormItem label="晚餐手续费（元）">
        <ElInputNumber
          v-model="feeForm.unsolicited_fee_dinner"
          :min="0"
          :max="9999"
          :precision="2"
          :step="0.5"
        />
      </ElFormItem>
    </ElForm>
    <div class="mb-4 rounded-lg bg-bg-secondary px-4 py-3 text-xs text-text-muted">
      <div class="font-medium text-text">规则说明</div>
      <ul class="mt-1 list-disc space-y-1 pl-5">
        <li>仅对「未订餐用餐」订单生效,正常订餐不受影响。</li>
        <li>手续费按订单收取(与菜品数量无关),计入订单实付金额。</li>
        <li>手续费设为 0 表示该餐别不收取。</li>
      </ul>
    </div>
    <template #footer>
      <div class="flex justify-end gap-3">
        <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="handleSave">保存配置</ElButton>
      </div>
    </template>
  </ElDialog>
</template>
