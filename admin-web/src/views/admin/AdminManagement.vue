<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Pencil, Trash2 } from 'lucide-vue-next'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { adminApi, storeApi } from '@/api'
import type { Admin, Store } from '@/api/types'
import { ADMIN_ROLE } from '@/constants/dict'
import StatusTag from '@/components/StatusTag.vue'

/** 角色下拉选项(超管创建时只能选 2/4/5/6,超管自身不可创建) */
const roleOptions = Object.entries(ADMIN_ROLE)
  .filter(([k]) => k !== '1')
  .map(([k, v]) => ({ value: Number(k), label: v.label }))

/** 角色映射(StatusTag 用) */
const roleMap = ADMIN_ROLE as Record<number, { label: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }>

const admins = ref<Admin[]>([])
const stores = ref<Store[]>([])
const loading = ref(false)

const fetchAdmins = async () => {
  loading.value = true
  try {
    admins.value = await adminApi.list()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    loading.value = false
  }
}

const fetchStores = async () => {
  try {
    stores.value = await storeApi.list()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

const storeName = (id?: number) => stores.value.find((s) => s.id === id)?.name ?? '—'

const dialogVisible = ref(false)
const isEdit = ref(false)
const dialogLoading = ref(false)
const formRef = ref<FormInstance>()
const form = ref<Admin>(defaultAdmin())

function defaultAdmin(): Admin {
  return { username: '', name: '', password: '', storeId: undefined, role: 2, status: 1 }
}

const rules: FormRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入管理员姓名', trigger: 'blur' }],
  storeId: [{ required: true, message: '请选择所属食堂', trigger: 'change' }],
}

const openAdd = () => {
  isEdit.value = false
  form.value = defaultAdmin()
  dialogVisible.value = true
}

const openEdit = (row: Admin) => {
  isEdit.value = true
  form.value = { ...row, password: '' }
  dialogVisible.value = true
}

const handleSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!isEdit.value && !form.value.password) {
    ElMessage.warning('请输入初始密码')
    return
  }
  dialogLoading.value = true
  try {
    if (isEdit.value && form.value.id) {
      await adminApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await adminApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchAdmins()
  } catch {
    /* 错误已由拦截器统一提示 */
  } finally {
    dialogLoading.value = false
  }
}

const handleDelete = async (row: Admin) => {
  if (!row.id) return
  if (row.role === 1) {
    ElMessage.warning('超级管理员账号不可删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要删除管理员「${row.name}」吗？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminApi.delete(row.id)
    ElMessage.success('删除成功')
    fetchAdmins()
  } catch {
    /* 错误已由拦截器统一提示 */
  }
}

onMounted(() => {
  fetchAdmins()
  fetchStores()
})
</script>

<template>
  <Layout>
    <PageContainer title="账号管理" description="超级管理员创建食堂管理员账号，并指派到对应食堂，各食堂管理员只能管理本食堂数据">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openAdd">添加管理员</ElButton>
      </template>

      <div class="card overflow-hidden">
        <ElTable v-loading="loading" :data="admins" style="width: 100%" row-key="id">
          <ElTableColumn prop="username" label="登录账号" min-width="140" />
          <ElTableColumn prop="name" label="姓名" min-width="120" />
          <ElTableColumn label="角色" width="120" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.role" :map="roleMap" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="所属食堂" min-width="160">
            <template #default="{ row }">
              {{ row.role === 1 ? '全部食堂' : storeName(row.storeId) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100" align="center">
            <template #default="{ row }">
              <ElTag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="200" fixed="right" align="left">
            <template #default="{ row }">
              <ElButton size="small" :icon="Pencil" @click="openEdit(row as Admin)">编辑</ElButton>
              <ElButton
                size="small"
                type="danger"
                :icon="Trash2"
                :disabled="row.role === 1"
                @click="handleDelete(row as Admin)"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无管理员账号，点击「添加管理员」开始创建" />
          </template>
        </ElTable>
      </div>

      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑管理员' : '添加管理员'"
        width="500px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm ref="formRef" :model="form" :rules="rules" label-width="90px">
          <ElFormItem label="登录账号" prop="username">
            <ElInput
              v-model="form.username"
              placeholder="请输入登录账号"
              :disabled="isEdit"
              maxlength="30"
            />
          </ElFormItem>
          <ElFormItem label="姓名" prop="name">
            <ElInput v-model="form.name" placeholder="请输入管理员姓名" maxlength="30" />
          </ElFormItem>
          <ElFormItem label="所属食堂" prop="storeId">
            <ElSelect v-model="form.storeId" placeholder="请选择所属食堂" class="w-full" :disabled="form.role === 1">
              <ElOption v-for="s in stores" :key="s.id" :label="s.name" :value="s.id!" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="角色">
            <ElSelect v-model="form.role" placeholder="请选择角色" class="w-full">
              <ElOption
                v-for="o in roleOptions"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </ElSelect>
            <span class="ml-2 text-xs text-text-muted">财务岗:报表+充值;厨师长:订餐汇总+菜品;店长:全店管理</span>
          </ElFormItem>
          <ElFormItem label="密码">
            <ElInput
              v-model="form.password"
              type="password"
              show-password
              :placeholder="isEdit ? '留空则不修改密码' : '请输入初始密码'"
            />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="form.status" class="w-full">
              <ElOption label="启用" :value="1" />
              <ElOption label="禁用" :value="0" />
            </ElSelect>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="dialogLoading" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
