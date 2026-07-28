<script setup lang="ts">
import { ElDialog, ElButton, ElForm } from 'element-plus'

interface Props {
  visible: boolean
  title: string
  width?: string | number
  loading?: boolean
}

withDefaults(defineProps<Props>(), {
  width: '500px',
  loading: false,
})

const emit = defineEmits<{
  (e: 'update:visible', val: boolean): void
  (e: 'save'): void
  (e: 'cancel'): void
}>()

const handleClose = () => {
  emit('update:visible', false)
  emit('cancel')
}

const handleSave = () => {
  emit('save')
}
</script>

<template>
  <ElDialog
    :model-value="visible"
    :title="title"
    :width="width"
    :close-on-click-modal="false"
    append-to-body
    destroy-on-close
    @update:model-value="handleClose"
  >
    <ElForm label-width="100px" label-position="right" class="pr-2">
      <slot name="form" />
    </ElForm>
    <template #footer>
      <div class="flex justify-end gap-3">
        <ElButton @click="handleClose">取消</ElButton>
        <ElButton type="primary" :loading="loading" @click="handleSave">
          保存
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>
