<script setup lang="ts">
/**
 * 右上角时钟组件(紧凑横向布局)
 * - 显示 HH:MM 大字 + 下方日期/星期
 * - 每秒更新
 * - 用于 TopBar 右侧 / 待机页右上角
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { pad2 } from '@/utils'

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const clock = ref('')
const dateLine = ref('')

let timer = 0

const update = () => {
  const now = new Date()
  clock.value = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`
  dateLine.value = `${now.getMonth() + 1}月${now.getDate()}日 ${WEEK[now.getDay()]}`
}

onMounted(() => {
  update()
  timer = window.setInterval(update, 1000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="clock-widget">
    <div class="clock-widget__time">{{ clock }}</div>
    <div class="clock-widget__date">{{ dateLine }}</div>
  </div>
</template>

<style scoped>
.clock-widget {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  line-height: 1.1;
  user-select: none;
}
.clock-widget__time {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--doubao-foreground);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.5px;
}
.clock-widget__date {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--doubao-muted-foreground);
}

/* 待机页(深色背景)用法:在父级加 .clock-widget--inverse 修饰 */
.clock-widget--inverse .clock-widget__time {
  color: white;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}
.clock-widget--inverse .clock-widget__date {
  color: rgba(255, 255, 255, 0.85);
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}
</style>
