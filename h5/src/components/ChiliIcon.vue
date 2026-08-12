<script setup lang="ts">
/**
 * 辣椒图标(自定义 SVG)。
 * lucide-vue-next 当前版本(0.468.0)无 Pepper 图标,故自绘。
 * 辣椒主体颜色通过 currentColor 继承(默认红色),茎部固定绿色。
 */
withDefaults(
  defineProps<{
    size?: number | string
  }>(),
  {
    size: 16,
  },
)
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    class="chili-icon"
    aria-hidden="true"
  >
    <!-- 辣椒茎(绿色) -->
    <path d="M16 9c0 -3 0 -5 -2 -7" stroke="#16a34a" />
    <!-- 辣椒主体 -->
    <path d="M13 10c-3.4 -1 -4 -1 -5 2c-1 3 -4 7 -1 7c3 0 6 -2 7 -5c1 -3 1 -4 -1 -4z" />
  </svg>
</template>
