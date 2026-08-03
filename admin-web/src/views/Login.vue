<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { Utensils, User, Lock, Eye, EyeOff } from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({
  username: '',
  password: '',
})
const showPassword = ref(false)
const loading = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
}

const handleLogin = async () => {
  // 防止 loading 期间重复提交(按回车键可能触发多次)
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await authStore.login(form.username, form.password)
    ElMessage.success('登录成功')
    const rawRedirect = (route.query.redirect as string) || '/dashboard'
    const isSafeRedirect = (p: string) => p.startsWith('/') && !p.startsWith('//') && !p.includes('://')
    const redirect = isSafeRedirect(rawRedirect) ? rawRedirect : '/dashboard'
    router.push(redirect)
  } catch {
    // 错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex bg-bg-secondary">
    <!-- 左侧品牌区 -->
    <div
      class="relative hidden lg:flex lg:w-1/2 flex-col justify-between overflow-hidden bg-gradient-to-br from-primary-600 via-primary-500 to-accent-600 p-12 text-white"
    >
      <!-- 装饰几何图形 -->
      <div class="absolute -left-24 -top-24 h-96 w-96 rounded-full bg-white/10 blur-3xl" />
      <div class="absolute bottom-0 right-0 h-80 w-80 rounded-full bg-accent-400/20 blur-3xl" />
      <div class="absolute right-10 top-1/3 h-32 w-32 rotate-12 rounded-3xl border border-white/20" />
      <div class="absolute left-1/4 bottom-1/4 h-20 w-20 rounded-2xl border border-white/15 rotate-45" />

      <!-- 顶部 Logo -->
      <div class="relative flex items-center gap-3">
        <div class="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/20 backdrop-blur">
          <Utensils class="h-6 w-6" />
        </div>
        <span class="text-xl font-bold tracking-tight">企业智慧食堂</span>
      </div>

      <!-- 中部 Slogan -->
      <div class="relative">
        <h1 class="text-4xl font-bold leading-tight">
          智慧餐饮<br />高效管理
        </h1>
        <p class="mt-4 max-w-md text-white/80">
          一站式企业食堂管理平台，让每一餐都更智能、更高效、更美味。
        </p>
        <div class="mt-10 flex gap-10">
          <div>
            <div class="text-2xl font-bold">智能</div>
            <div class="mt-1 text-sm text-white/70">数据驱动运营</div>
          </div>
          <div>
            <div class="text-2xl font-bold">高效</div>
            <div class="mt-1 text-sm text-white/70">极简管理流程</div>
          </div>
          <div>
            <div class="text-2xl font-bold">美味</div>
            <div class="mt-1 text-sm text-white/70">菜品实时掌控</div>
          </div>
        </div>
      </div>

      <!-- 底部 -->
      <div class="relative text-sm text-white/60">© 2026 企业智慧食堂 管理端</div>
    </div>

    <!-- 右侧登录卡片 -->
    <div class="flex w-full items-center justify-center p-4 lg:w-1/2 sm:p-8">
      <div
        class="w-full max-w-md rounded-2xl border border-border bg-card/80 p-8 shadow-2xl backdrop-blur-xl sm:p-10"
      >
        <!-- 移动端 Logo -->
        <div class="mb-8 flex items-center gap-3 lg:hidden">
          <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-primary">
            <Utensils class="h-6 w-6 text-white" />
          </div>
          <span class="text-lg font-bold text-text">企业智慧食堂</span>
        </div>

        <h2 class="text-2xl font-bold text-text">欢迎回来</h2>
        <p class="mt-1.5 text-sm text-text-secondary">请登录您的管理员账号</p>

        <ElForm
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="mt-8"
          aria-label="登录表单"
          @submit.prevent
        >
          <ElFormItem label="用户名" prop="username">
            <ElInput
              v-model="form.username"
              :prefix-icon="User"
              placeholder="请输入用户名"
              size="large"
              autocomplete="username"
              aria-label="用户名"
              @keyup.enter="handleLogin"
            />
          </ElFormItem>

          <ElFormItem label="密码" prop="password">
            <ElInput
              v-model="form.password"
              :prefix-icon="Lock"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
              size="large"
              autocomplete="current-password"
              aria-label="密码"
              @keyup.enter="handleLogin"
            >
              <template #suffix>
                <button
                  type="button"
                  class="flex h-6 w-6 cursor-pointer items-center justify-center text-text-muted transition-colors hover:text-primary"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  @click="showPassword = !showPassword"
                >
                  <component :is="showPassword ? EyeOff : Eye" class="h-4 w-4" />
                </button>
              </template>
            </ElInput>
          </ElFormItem>

          <ElButton
            type="primary"
            size="large"
            class="mt-2 w-full text-base font-semibold"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </ElButton>
        </ElForm>

        <p class="mt-6 text-center text-xs text-text-muted">
          企业智慧食堂管理系统 · 安全登录
        </p>
      </div>
    </div>
  </div>
</template>
