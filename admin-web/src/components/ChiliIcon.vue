<script setup lang="ts">
/**
 * 辣椒图标(自定义 SVG)。
 * 红彤彤的辣椒:红色弯曲锥形主体 + 绿色茎部 + 白色高光。
 * 主体颜色通过 currentColor 继承(各调用处设红色文字色),茎部固定绿色。
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
    class="chili-icon"
    aria-hidden="true"
  >
    <!-- 绿色茎部(固定绿色) -->
    <path
      d="M14.5 7.5c0-2 .8-3.8 2.2-5.2c.4-.4.8-.7 1.3-1"
      stroke="#22c55e"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <!-- 红色辣椒主体(弯曲锥形,底部尖;fill 用 currentColor 继承父级红色) -->
    <path
      d="M13.5 7c-2-.8-3.8-.4-5.2 1C5.5 11 3 14.5 3 18c0 1.8 1.2 3 3 3c4.2 0 8.2-4 9.8-8.5c.7-2 .3-3.8-1.3-4.8z"
      fill="currentColor"
    />
    <!-- 白色高光(增加立体感) -->
    <path
      d="M8 11c-1.3 1.5-2.5 3.5-3 5.5"
      stroke="#ffffff"
      stroke-width="1.2"
      stroke-linecap="round"
      opacity="0.4"
    />
  </svg>
</template>
