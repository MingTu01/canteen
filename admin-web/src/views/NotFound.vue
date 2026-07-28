<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import { Home, Compass } from 'lucide-vue-next'

const router = useRouter()
</script>

<template>
  <div class="flex min-h-screen flex-col items-center justify-center px-4">
    <div class="flex h-24 w-24 items-center justify-center rounded-3xl bg-primary-100">
      <Compass class="h-12 w-12 text-primary" />
    </div>
    <h1 class="mt-8 text-7xl font-bold tracking-tight text-text">404</h1>
    <p class="mt-3 text-lg text-text-secondary">抱歉，您访问的页面不存在</p>
    <p class="mt-1 text-sm text-text-muted">请检查地址是否正确，或返回首页继续操作</p>
    <ElButton
      type="primary"
      size="large"
      class="mt-8 rounded-xl"
      :icon="Home"
      @click="router.push('/dashboard')"
    >
      返回首页
    </ElButton>
  </div>
</template>
