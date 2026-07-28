<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from 'element-plus'

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

interface StatusMeta {
  label: string
  type?: TagType
}

interface Props {
  value: number | string
  map: Record<string | number, StatusMeta>
}

const props = defineProps<Props>()

const meta = computed<StatusMeta | undefined>(() => props.map[props.value])
const tagType = computed<TagType>(() => meta.value?.type ?? 'primary')
</script>

<template>
  <ElTag v-if="meta" :type="tagType" effect="light" round size="small" role="status">
    {{ meta.label }}
  </ElTag>
  <span v-else class="text-sm text-text-muted" role="status">{{ value }}</span>
</template>
