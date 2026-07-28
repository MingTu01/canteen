<script setup lang="ts">
/**
 * 无操作超时计时器(终端防遗忘)
 * - 监听 click/keydown/touchstart 事件
 * - 超时后调用 onTimeout 回调(通常返回待机页)
 * - 通过 onMounted/onUnmounted 自动管理事件监听
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

const props = withDefaults(defineProps<{
  timeout?: number // 毫秒,默认 120s
  onTimeout?: () => void
  checkInterval?: number // 检查间隔,默认 5s
}>(), {
  timeout: 120_000,
  checkInterval: 5_000,
})

const lastActivity = ref(Date.now())

const updateActivity = () => { lastActivity.value = Date.now() }
let timer = 0

const checkIdle = () => {
  if (Date.now() - lastActivity.value >= props.timeout) {
    props.onTimeout?.()
  }
}

onMounted(() => {
  window.addEventListener('click', updateActivity)
  window.addEventListener('keydown', updateActivity)
  window.addEventListener('touchstart', updateActivity)
  timer = window.setInterval(checkIdle, props.checkInterval)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', updateActivity)
  window.removeEventListener('keydown', updateActivity)
  window.removeEventListener('touchstart', updateActivity)
  if (timer) clearInterval(timer)
})

/** 重置计时器(外部可调用) */
defineExpose({ reset: () => { lastActivity.value = Date.now() } })
</script>

<template></template>
