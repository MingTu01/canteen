<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElTag,
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Pencil, Trash2, Store as StoreIcon, KeyRound, Copy, Eye, ArrowRight, Image as ImageIcon } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import { storeApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { Store } from '@/api/types'

const router = useRouter()
const authStore = useAuthStore()

const stores = ref<Store[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(9)

const total = computed(() => stores.value.length)
const pagedStores = computed(() => {
  const start = (page.value - 1) * size.value
  return stores.value.slice(start, start + size.value)
})

const fetchStores = async () => {
  loading.value = true
  try {
    stores.value = await storeApi.list()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

/* ===== 创建/编辑弹窗 ===== */
const dialogVisible = ref(false)
const isEdit = ref(false)
const dialogLoading = ref(false)
const formRef = ref<FormInstance>()
const form = ref<Store>(defaultStore())

function defaultStore(): Store {
  return {
    name: '',
    code: '',
    address: '',
    phone: '',
    status: 1,
    description: '',
  }
}

const rules: FormRules = {
  name: [{ required: true, message: '请输入食堂名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入食堂编码', trigger: 'blur' }],
}

const openAdd = () => {
  isEdit.value = false
  form.value = defaultStore()
  dialogVisible.value = true
}

const openEdit = (row: Store) => {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

const handleSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  dialogLoading.value = true
  try {
    if (isEdit.value && form.value.id) {
      await storeApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await storeApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchStores()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    dialogLoading.value = false
  }
}

const handleDelete = async (row: Store) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `确定要删除食堂「${row.name}」吗？删除后该食堂的所有数据将无法访问。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await storeApi.delete(row.id)
    ElMessage.success('删除成功')
    fetchStores()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

/* ===== 进入管理(切换当前食堂) ===== */
const switching = ref<number | null>(null)

const handleEnterManage = async (row: Store) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `切换到食堂「${row.name}」进行管理？切换后所有页面将显示该食堂的数据。`,
      '切换食堂',
      { type: 'info', confirmButtonText: '进入管理', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  switching.value = row.id
  try {
    const result = await storeApi.switchTo(row.id)
    // 更新本地 auth store 的 storeId(供 Layout 显示)
    if (authStore.admin) {
      authStore.admin.storeId = result.storeId
    }
    ElMessage.success(`已切换到「${result.storeName}」`)
    router.push('/dashboard')
  } catch {
    /* 拦截器已提示 */
  } finally {
    switching.value = null
  }
}

/* ===== 安全码管理 ===== */
const securityCodeVisible = ref(false)
const securityCodeStore = ref<Store | null>(null)
const resetting = ref(false)
const showCode = ref(false)

const openSecurityCode = (row: Store) => {
  securityCodeStore.value = row
  showCode.value = false
  securityCodeVisible.value = true
}

const handleResetCode = async () => {
  if (!securityCodeStore.value?.id) return
  try {
    await ElMessageBox.confirm(
      `重置「${securityCodeStore.value.name}」的安全码后,使用旧安全码绑定的终端将无法继续使用,需重新绑定。确认重置?`,
      '重置安全码',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    const res = await storeApi.resetSecurityCode(securityCodeStore.value.id)
    // 更新本地列表中的安全码
    const idx = stores.value.findIndex((s) => s.id === res.id)
    if (idx >= 0) {
      stores.value[idx] = { ...stores.value[idx], securityCode: res.securityCode }
    }
    securityCodeStore.value = { ...securityCodeStore.value!, securityCode: res.securityCode }
    showCode.value = true
    ElMessage.success('安全码已重置,请将新安全码提供给终端配置人员')
  } catch {
    /* 拦截器提示 */
  } finally {
    resetting.value = false
  }
}

const copyCode = async () => {
  if (!securityCodeStore.value?.securityCode) return
  try {
    await navigator.clipboard.writeText(securityCodeStore.value.securityCode)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败,请手动选择文本复制')
  }
}

/* ===== 品牌信息编辑弹窗 ===== */
const brandingVisible = ref(false)
const brandingStore = ref<Store | null>(null)
const brandingLoading = ref(false)
const brandingForm = ref<{
  logoUrl: string
  imageUrl: string
  terminalBackgroundUrl: string
  h5BannerUrl: string
  description: string
}>({ logoUrl: '', imageUrl: '', terminalBackgroundUrl: '', h5BannerUrl: '', description: '' })

const openBranding = (row: Store) => {
  brandingStore.value = row
  brandingForm.value = {
    logoUrl: row.logoUrl || '',
    imageUrl: row.imageUrl || '',
    terminalBackgroundUrl: row.terminalBackgroundUrl || '',
    h5BannerUrl: row.h5BannerUrl || '',
    description: row.description || '',
  }
  brandingVisible.value = true
}

const handleSaveBranding = async () => {
  if (!brandingStore.value?.id) return
  brandingLoading.value = true
  try {
    const updated = await storeApi.updateBranding(brandingStore.value.id, brandingForm.value)
    // 更新本地列表
    const idx = stores.value.findIndex((s) => s.id === updated.id)
    if (idx >= 0) {
      stores.value[idx] = { ...stores.value[idx], ...brandingForm.value }
    }
    ElMessage.success('品牌信息已更新,各端将在下次进入页面时自动刷新')
    brandingVisible.value = false
  } catch {
    /* 拦截器已提示 */
  } finally {
    brandingLoading.value = false
  }
}

onMounted(fetchStores)
</script>

<template>
  <Layout>
    <PageContainer title="食堂管理" description="超级管理员创建与管理多个食堂，配置品牌资源（Logo/图片/终端背景图），点击「进入管理」切换当前管理食堂">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openAdd">创建食堂</ElButton>
      </template>

      <!-- 卡片式列表 -->
      <div v-loading="loading" class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="store in pagedStores"
          :key="store.id"
          class="group flex flex-col overflow-hidden rounded-xl border border-border bg-card shadow-sm transition-all hover:shadow-md"
        >
          <!-- 食堂图片 -->
          <div class="relative h-40 overflow-hidden bg-bg-tertiary">
            <img
              v-if="store.imageUrl"
              :src="store.imageUrl"
              :alt="store.name"
              class="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
            />
            <div v-else class="flex h-full items-center justify-center text-text-muted">
              <StoreIcon class="h-12 w-12" />
            </div>
            <!-- Logo 角标 -->
            <img
              v-if="store.logoUrl"
              :src="store.logoUrl"
              :alt="store.name + ' Logo'"
              class="absolute bottom-2 left-2 h-10 w-10 rounded-lg border-2 border-white bg-white object-cover shadow"
            />
            <!-- 状态标签 -->
            <div class="absolute right-2 top-2">
              <ElTag :type="store.status === 1 ? 'success' : 'info'" size="small" effect="dark">
                {{ store.status === 1 ? '营业中' : '已停用' }}
              </ElTag>
            </div>
          </div>

          <!-- 信息区 -->
          <div class="flex flex-1 flex-col p-4">
            <div class="mb-1 flex items-center gap-2">
              <h3 class="truncate text-base font-semibold text-text">{{ store.name }}</h3>
              <code class="rounded bg-bg-tertiary px-1.5 py-0.5 text-xs text-text-muted">{{ store.code }}</code>
            </div>
            <p v-if="store.description" class="mb-2 line-clamp-2 text-xs text-text-muted">{{ store.description }}</p>
            <p v-if="store.address" class="mb-3 truncate text-xs text-text-secondary">{{ store.address }}</p>

            <!-- 操作按钮 -->
            <div class="mt-auto flex flex-wrap items-center gap-1.5">
              <ElButton
                type="primary"
                size="small"
                :icon="ArrowRight"
                :loading="switching === store.id"
                @click="handleEnterManage(store)"
              >
                进入管理
              </ElButton>
              <ElButton size="small" :icon="ImageIcon" @click="openBranding(store)">品牌</ElButton>
              <ElButton size="small" :icon="Pencil" @click="openEdit(store)">编辑</ElButton>
              <ElButton link type="primary" size="small" :icon="KeyRound" @click="openSecurityCode(store)">
                安全码
              </ElButton>
              <ElButton size="small" type="danger" :icon="Trash2" @click="handleDelete(store)" />
            </div>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-if="!loading && pagedStores.length === 0" class="col-span-full">
          <EmptyState description="暂无食堂数据，点击「创建食堂」开始添加" />
        </div>
      </div>

      <!-- 分页 -->
      <div v-if="total > size" class="mt-4 flex items-center justify-center">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[9, 18, 27, 36]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>

      <!-- 创建/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑食堂' : '创建食堂'"
        width="520px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="90px">
          <ElFormItem label="食堂名称" prop="name">
            <ElInput v-model="form.name" placeholder="请输入食堂名称" maxlength="50" />
          </ElFormItem>
          <ElFormItem label="食堂编码" prop="code">
            <ElInput v-model="form.code" placeholder="唯一编码，如 CANTEEN-001" maxlength="30" />
          </ElFormItem>
          <ElFormItem label="地址">
            <ElInput v-model="form.address" placeholder="请输入食堂地址" maxlength="120" />
          </ElFormItem>
          <ElFormItem label="联系电话">
            <ElInput v-model="form.phone" placeholder="请输入联系电话" maxlength="20" />
          </ElFormItem>
          <ElFormItem label="食堂简介">
            <ElInput v-model="form.description" type="textarea" :rows="2" placeholder="一句话介绍食堂" maxlength="200" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElInput v-model="form.status" :placeholder="form.status === 1 ? '营业中' : '已停用'" disabled />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>

      <!-- 品牌信息编辑弹窗 -->
      <ElDialog
        v-model="brandingVisible"
        title="食堂品牌信息"
        width="560px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div v-if="brandingStore" class="space-y-5">
          <p class="text-sm text-text-secondary">
            配置食堂「<span class="font-medium text-text">{{ brandingStore.name }}</span>」的品牌资源，修改后各端（H5 订餐端、取餐终端）将在下次进入页面时自动刷新。
          </p>

          <div>
            <label class="mb-1.5 block text-sm font-medium text-text">企业 Logo</label>
            <ImageUploader
              v-model="brandingForm.logoUrl"
              label="Logo"
              hint="显示在 H5 顶部、取餐终端顶栏、admin-web 侧栏(建议 1:1 正方形,请上传 PNG 格式以保留透明背景)"
              :preview-size="80"
              loose
            />
          </div>

          <div>
            <label class="mb-1.5 block text-sm font-medium text-text">食堂图片</label>
            <ImageUploader
              v-model="brandingForm.imageUrl"
              label="食堂图片"
              hint="显示在食堂列表卡片、H5 登录页选择(建议 16:9 横图)"
              :preview-size="120"
              loose
            />
          </div>

          <div>
            <label class="mb-1.5 block text-sm font-medium text-text">取餐终端背景图</label>
            <ImageUploader
              v-model="brandingForm.terminalBackgroundUrl"
              label="终端背景"
              hint="取餐终端待机页的主图/背景(建议 16:9 横图,留白以显示按钮)"
              :preview-size="120"
              loose
            />
          </div>

          <div>
            <label class="mb-1.5 block text-sm font-medium text-text">H5 顶部 Banner(可选)</label>
            <ImageUploader
              v-model="brandingForm.h5BannerUrl"
              label="H5 Banner"
              hint="H5 订餐端首页顶部 banner(可选,留空则显示食堂名)"
              :preview-size="120"
              loose
            />
          </div>

          <div>
            <label class="mb-1.5 block text-sm font-medium text-text">食堂简介</label>
            <ElInput
              v-model="brandingForm.description"
              type="textarea"
              :rows="2"
              placeholder="一句话介绍食堂,显示在各端"
              maxlength="200"
              show-word-limit
            />
          </div>
        </div>
        <template #footer>
          <ElButton @click="brandingVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="brandingLoading" @click="handleSaveBranding">保存品牌信息</ElButton>
        </template>
      </ElDialog>

      <!-- 安全码查看/重置弹窗 -->
      <ElDialog
        v-model="securityCodeVisible"
        title="食堂安全码"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <div v-if="securityCodeStore" class="space-y-4">
          <p class="text-sm text-text-secondary">
            食堂「<span class="font-medium text-text">{{ securityCodeStore.name }}</span>」的安全码用于终端(X86 设备)绑定。终端配置时需同时提供管理员账号密码 + 此安全码才能绑定成功。
          </p>
          <div class="rounded-lg border border-border bg-secondary/50 p-4">
            <div class="text-xs text-text-muted mb-2">当前安全码</div>
            <div class="flex items-center gap-3">
              <code class="flex-1 text-lg font-mono font-bold tracking-widest text-text">
                {{ showCode && securityCodeStore.securityCode ? securityCodeStore.securityCode : '••••••••' }}
              </code>
              <ElButton link :icon="showCode ? Eye : Eye" @click="showCode = !showCode">
                {{ showCode ? '隐藏' : '显示' }}
              </ElButton>
              <ElButton link :icon="Copy" :disabled="!securityCodeStore.securityCode" @click="copyCode">
                复制
              </ElButton>
            </div>
          </div>
          <div class="rounded-lg bg-amber-50 border border-amber-200 p-3 text-xs text-amber-700">
            <strong>注意:</strong>重置安全码后,使用旧安全码绑定的终端将立即失效,需重新执行绑定流程。
          </div>
        </div>
        <template #footer>
          <ElButton @click="securityCodeVisible = false">关闭</ElButton>
          <ElButton type="warning" :icon="KeyRound" :loading="resetting" @click="handleResetCode">
            重置安全码
          </ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
