<script setup lang="ts">
import { ref, computed } from 'vue'
import { onMounted } from 'vue'
import Layout from '@/components/Layout.vue'
import PageContainer from '@/components/PageContainer.vue'
import SearchBar from '@/components/SearchBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useCrud } from '@/composables/useCrud'
import { useAuthStore } from '@/stores/auth'
import { departmentApi } from '@/api'
import type { Department } from '@/api'
import { COMMON_STATUS } from '@/constants/dict'
import {
  ElTable,
  ElTableColumn,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElSelect,
  ElOption,
  ElSwitch,
  ElMessage,
} from 'element-plus'
import { Plus, Building2, Pencil, Trash2 } from 'lucide-vue-next'

const authStore = useAuthStore()
// 超管未选择食堂时返回 null,不再静默回退到 storeId=1
const storeId = computed(() => authStore.storeId || null)

const keyword = ref('')

interface DeptNode extends Department {
  children?: DeptNode[]
  hasChildren?: boolean
}

const {
  list,
  loading,
  dialogVisible,
  dialogLoading,
  isEdit,
  form,
  fetchList,
  handleCreate,
  handleEdit,
  handleDelete,
} = useCrud<Department>({
  entityName: '部门',
  list: async () => {
    const sid = storeId.value
    if (!sid) return []
    return departmentApi.list(sid)
  },
  create: (data) => departmentApi.create(data),
  update: (id, data) => departmentApi.update(id, data),
  remove: (id) => departmentApi.delete(id),
})

/** 扁平转树 */
const buildTree = (items: Department[]): DeptNode[] => {
  const map = new Map<number, DeptNode>()
  const roots: DeptNode[] = []
  items.forEach((it) => map.set(it.id as number, { ...it, children: [] }))
  map.forEach((node) => {
    const pid = node.parentId
    if (pid && map.has(pid)) {
      map.get(pid)!.children!.push(node)
    } else {
      roots.push(node)
    }
  })
  const sortRec = (nodes: DeptNode[]) => {
    nodes.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
    nodes.forEach((n) => {
      if (n.children && n.children.length) sortRec(n.children)
      else delete n.children
    })
  }
  sortRec(roots)
  return roots
}

const treeData = computed<DeptNode[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  const filtered = kw
    ? list.value.filter((d) => d.name.toLowerCase().includes(kw))
    : list.value
  return buildTree(filtered)
})

/** 名称映射,用于显示上级与下拉 */
const nameMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  list.value.forEach((d) => {
    if (d.id != null) m[d.id] = d.name
  })
  return m
})

/** 收集某节点的所有后代 id(用于选择上级时排除,避免成环) */
const descendantIds = (id: number): Set<number> => {
  const result = new Set<number>()
  const walk = (pid: number) => {
    list.value.forEach((d) => {
      if (d.parentId === pid && d.id != null) {
        result.add(d.id)
        walk(d.id)
      }
    })
  }
  walk(id)
  return result
}

const parentOptions = computed<Department[]>(() => {
  const exclude = form.value.id ? descendantIds(form.value.id) : new Set<number>()
  if (form.value.id) exclude.add(form.value.id)
  return list.value.filter((d) => d.id != null && !exclude.has(d.id))
})

const saving = ref(false)
const handleSave = async () => {
  if (!form.value.name?.trim()) {
    ElMessage.warning('请输入部门名称')
    return
  }
  saving.value = true
  try {
    const payload: Department = {
      ...form.value,
      name: form.value.name.trim(),
      storeId: storeId.value ?? 0,
      parentId: form.value.parentId || 0,
      status: form.value.status ?? 1,
      sort: form.value.sort ?? 0,
    }
    if (isEdit.value && form.value.id) {
      await departmentApi.update(form.value.id, payload)
      ElMessage.success('更新成功')
    } else {
      await departmentApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await fetchList()
  } catch {
    /* 错误已由拦截器提示 */
  } finally {
    saving.value = false
  }
}

const openCreate = () => {
  handleCreate()
  form.value = { storeId: storeId.value ?? 0, name: '', parentId: 0, sort: 0, status: 1 }
}

const openEdit = (row: Department) => {
  handleEdit(row)
}

onMounted(fetchList)
</script>

<template>
  <Layout>
    <PageContainer title="部门管理" description="维护企业部门层级结构,支持树形展示与上级关联。">
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">新增部门</ElButton>
      </template>

      <div
        v-if="!storeId"
        class="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-700"
      >
        请先选择食堂后再查看数据。
      </div>

      <SearchBar @search="fetchList" @reset="keyword = ''">
        <ElInput
          v-model="keyword"
          placeholder="搜索部门名称"
          clearable
          style="width: 260px"
          @keyup.enter="fetchList"
        />
      </SearchBar>

      <div
        class="rounded-xl border border-border bg-card shadow-sm overflow-hidden"
        v-loading="loading"
      >
        <ElTable
          :data="treeData"
          row-key="id"
          :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
          default-expand-all
          style="width: 100%"
        >
          <ElTableColumn prop="name" label="部门名称" min-width="220">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <Building2 class="h-4 w-4 text-primary" />
                <span class="font-medium text-text">{{ row.name }}</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="上级部门" width="160">
            <template #default="{ row }">
              <span class="text-text-secondary">
                {{ row.parentId ? nameMap[row.parentId] || '—' : '无' }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="sort" label="排序" width="100" align="center">
            <template #default="{ row }">
              <span class="tabular-nums text-text-secondary">{{ row.sort ?? 0 }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110" align="center">
            <template #default="{ row }">
              <StatusTag :value="row.status ?? 1" :map="COMMON_STATUS" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <ElButton link type="primary" :icon="Pencil" @click="openEdit(row as Department)">编辑</ElButton>
              <ElButton link type="danger" :icon="Trash2" @click="handleDelete(row.id)">删除</ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <EmptyState description="暂无部门数据,点击「新增部门」开始创建" />
          </template>
        </ElTable>
      </div>

      <ElDialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑部门' : '新增部门'"
        width="480px"
        :close-on-click-modal="false"
        append-to-body
        destroy-on-close
      >
        <ElForm :model="form" label-width="90px" label-position="right" class="pr-2">
          <ElFormItem label="部门名称" required>
            <ElInput v-model="form.name" placeholder="请输入部门名称" maxlength="30" />
          </ElFormItem>
          <ElFormItem label="上级部门">
            <ElSelect v-model="form.parentId" placeholder="无" clearable style="width: 100%">
              <ElOption label="无（顶级部门）" :value="0" />
              <ElOption
                v-for="d in parentOptions"
                :key="d.id"
                :label="d.name"
                :value="d.id as number"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber v-model="form.sort" :min="0" :max="9999" controls-position="right" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <div class="flex justify-end gap-3">
            <ElButton @click="dialogVisible = false">取消</ElButton>
            <ElButton type="primary" :loading="saving || dialogLoading" @click="handleSave">保存</ElButton>
          </div>
        </template>
      </ElDialog>
    </PageContainer>
  </Layout>
</template>
