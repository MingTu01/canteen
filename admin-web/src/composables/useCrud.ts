import { reactive, ref, shallowRef } from 'vue'
import type { Reactive, Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { PageResult } from '@/api/types'

/** 列表返回值:数组(无分页)或 PageResult<T>(带分页) */
export type ListResult<T> = T[] | PageResult<T>

/** 搜索参数默认类型:可被泛型 Q 扩展 */
export type SearchParams = Record<string, unknown>

/** 传递给 list 回调的查询参数 */
export interface CrudQuery<Q> {
  page: number
  size: number
  search: Q
}

export interface CrudConfig<T, Q = SearchParams> {
  /**
   * 拉取列表:可返回数组(无分页)或 PageResult<T>(带分页)
   * 已有的忽略参数的旧实现 (`async () => T[]`) 仍可工作
   */
  list: (params: CrudQuery<Q>) => Promise<ListResult<T>>
  create?: (data: T) => Promise<unknown>
  update?: (id: number, data: T) => Promise<unknown>
  remove?: (id: number) => Promise<unknown>
  /** 实体名称(用于删除提示) */
  entityName?: string
  /** 表单校验规则;传入则在保存前调用 formRef.validate() */
  rules?: FormRules
  /** 新增时默认表单值 */
  defaultForm?: () => T
  /** 是否在删除前二次确认(默认 true) */
  confirmDelete?: boolean
  /** 默认页大小(默认 10) */
  defaultSize?: number
  /** 初始搜索参数 */
  initialSearch?: Q
}

/**
 * 通用 CRUD 组合式函数
 *
 * 封装列表加载、分页、搜索、新增/编辑弹窗、表单校验、保存、删除等通用逻辑。
 *
 * 向后兼容:已有的 `list: async () => T[]` 调用可继续工作(参数可选、返回数组接受)。
 * 新调用方可通过返回的 page/size/total/searchParams/onPageChange/onSizeChange/handleSearch/handleReset
 * 直接享受分页与搜索能力,无需在外部维护这些状态。
 */
export function useCrud<
  T extends { id?: number },
  Q = SearchParams
>(config: CrudConfig<T, Q>) {
  /* ---------- 列表与分页 ---------- */
  const list: Ref<T[]> = ref([])
  const loading = ref(false)
  const page = ref(1)
  const size = ref(config.defaultSize ?? 10)
  const total = ref(0)

  /* ---------- 搜索参数(响应式,可双向绑定到表单控件) ---------- */
  const searchParams: Reactive<Q> = reactive(
    ({ ...(config.initialSearch ?? {}) }) as object
  ) as Reactive<Q>

  const fetchList = async () => {
    loading.value = true
    try {
      const res = await config.list({
        page: page.value,
        size: size.value,
        search: searchParams as Q,
      })
      if (Array.isArray(res)) {
        list.value = res
        total.value = res.length
      } else {
        list.value = res.records ?? []
        total.value = Number(res.total ?? 0)
      }
    } catch {
      /* 错误已由 axios 拦截器统一提示 */
    } finally {
      loading.value = false
    }
  }

  const onPageChange = (p: number): void => {
    page.value = p
    fetchList()
  }

  const onSizeChange = (s: number): void => {
    size.value = s
    page.value = 1
    fetchList()
  }

  const handleSearch = (): void => {
    page.value = 1
    fetchList()
  }

  const handleReset = (): void => {
    // 清空所有搜索字段
    Object.keys(searchParams).forEach((k) => {
      ;(searchParams as Record<string, unknown>)[k] = undefined
    })
    // 还原初始值
    if (config.initialSearch) {
      Object.entries(config.initialSearch).forEach(([k, v]) => {
        ;(searchParams as Record<string, unknown>)[k] = v
      })
    }
    page.value = 1
    fetchList()
  }

  /* ---------- 弹窗与表单 ---------- */
  const dialogVisible = ref(false)
  const dialogLoading = ref(false)
  const isEdit = ref(false)
  const formRef = ref<FormInstance>()
  const form: Ref<T> = shallowRef(
    (config.defaultForm?.() ?? ({} as T)) as T
  )

  const handleCreate = (): void => {
    isEdit.value = false
    form.value = config.defaultForm ? config.defaultForm() : ({} as T)
    dialogVisible.value = true
  }

  const handleEdit = (row: T): void => {
    isEdit.value = true
    form.value = { ...row }
    dialogVisible.value = true
  }

  const handleSave = async (): Promise<void> => {
    const { create, update, rules } = config
    if (!create || !update) return
    if (isEdit.value && !form.value.id) return

    // 表单校验(若提供了 rules 与 formRef)
    if (rules && formRef.value) {
      const valid = await formRef.value.validate().catch(() => false)
      if (!valid) return
    }

    dialogLoading.value = true
    try {
      if (isEdit.value && form.value.id) {
        await update(form.value.id, form.value)
        ElMessage.success('更新成功')
      } else {
        await create(form.value)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      fetchList()
    } catch {
      /* 错误已由 axios 拦截器统一提示 */
    } finally {
      dialogLoading.value = false
    }
  }

  const handleDelete = async (id: number): Promise<void> => {
    const { remove, confirmDelete = true } = config
    if (!remove) return

    if (confirmDelete) {
      try {
        await ElMessageBox.confirm(
          `确定要删除这条${config.entityName || '记录'}吗？`,
          '删除确认',
          {
            type: 'warning',
            confirmButtonText: '确定删除',
            cancelButtonText: '取消',
          }
        )
      } catch {
        return /* 用户取消 */
      }
    }

    try {
      await remove(id)
      ElMessage.success('删除成功')
      fetchList()
    } catch {
      /* 错误已由 axios 拦截器统一提示 */
    }
  }

  return {
    // 列表与分页
    list,
    loading,
    page,
    size,
    total,
    // 搜索
    searchParams,
    // 弹窗与表单
    dialogVisible,
    dialogLoading,
    isEdit,
    form,
    formRef,
    // 方法
    fetchList,
    onPageChange,
    onSizeChange,
    handleSearch,
    handleReset,
    handleCreate,
    handleEdit,
    handleSave,
    handleDelete,
  }
}
