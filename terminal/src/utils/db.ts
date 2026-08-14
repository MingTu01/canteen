/**
 * IndexedDB 封装:用于终端本地缓存菜品图片(Blob)和菜单数据。
 *
 * 设计要点:
 * - 单库 canteen_terminal,多 objectStore:dishes(菜品)、images(图片 Blob)、menus(菜单)
 * - 异步 API 基于 Promise,避免回调地狱
 * - 容错:IndexedDB 不可用时(隐私模式)所有方法 reject,调用方降级直查后端
 * - 图片以 Blob 形式存储,渲染时 URL.createObjectURL()
 * - 所有事务绑定 onabort,避免事务中止时 Promise 永久挂起
 */
const DB_NAME = 'canteen_terminal'
const DB_VERSION = 2
const STORE_DISHES = 'dishes'     // key: dishId(number), value: Dish 对象
const STORE_IMAGES = 'images'     // key: imageUrl(string), value: Blob
const STORE_MENUS = 'menus'       // key: `${storeId}_${date}`, value: 菜单数组
const STORE_EMPLOYEES = 'employees' // key: cardNo(string), value: Employee 对象

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      dbPromise = null
      reject(new Error('IndexedDB 不可用'))
      return
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_DISHES)) {
        db.createObjectStore(STORE_DISHES, { keyPath: 'id' })
      }
      if (!db.objectStoreNames.contains(STORE_IMAGES)) {
        db.createObjectStore(STORE_IMAGES)
      }
      if (!db.objectStoreNames.contains(STORE_MENUS)) {
        db.createObjectStore(STORE_MENUS)
      }
      if (!db.objectStoreNames.contains(STORE_EMPLOYEES)) {
        db.createObjectStore(STORE_EMPLOYEES, { keyPath: 'cardNo' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => {
      // 失败时清除缓存,允许下次重试(否则会永久返回 rejected Promise)
      dbPromise = null
      reject(req.error)
    }
  })
  return dbPromise
}

function tx<T>(
  store: string,
  mode: IDBTransactionMode,
  fn: (s: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const t = db.transaction(store, mode)
        const s = t.objectStore(store)
        const req = fn(s)
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
        // 事务中止时也要 reject,否则 Promise 会永久挂起(quota 超限/版本变更等场景)
        t.onabort = () => reject(t.error)
      }),
  )
}

/* ============ 菜品数据缓存 ============ */

export interface CachedDish {
  id: number
  storeId: number
  name: string
  price: number
  image: string | null
  category: string | null
  mealTypes: string | null
  status: number
  updatedAt: string
}

export async function dbPutDishes(dishes: CachedDish[]): Promise<void> {
  if (dishes.length === 0) return
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE_DISHES, 'readwrite')
    const s = t.objectStore(STORE_DISHES)
    for (const d of dishes) s.put(d)
    t.oncomplete = () => resolve()
    t.onerror = () => reject(t.error)
    t.onabort = () => reject(t.error)
  })
}

export async function dbGetDish(id: number): Promise<CachedDish | null> {
  try {
    return (await tx(STORE_DISHES, 'readonly', (s) => s.get(id))) ?? null
  } catch {
    return null
  }
}

export async function dbGetAllDishes(): Promise<CachedDish[]> {
  try {
    return (await tx(STORE_DISHES, 'readonly', (s) => s.getAll())) ?? []
  } catch {
    return []
  }
}

export async function dbClearDishesByStore(_storeId: number): Promise<void> {
  // 简化:全清后重写。菜品种类有限(<100),性能可接受
  const db = await openDb()
  return new Promise<void>((resolve, reject) => {
    const t = db.transaction(STORE_DISHES, 'readwrite')
    t.objectStore(STORE_DISHES).clear()
    t.oncomplete = () => resolve()
    t.onerror = () => reject(t.error)
    t.onabort = () => reject(t.error)
  })
}

/* ============ 图片 Blob 缓存 ============ */

export async function dbPutImage(url: string, blob: Blob): Promise<void> {
  try {
    await tx(STORE_IMAGES, 'readwrite', (s) => s.put(blob, url))
  } catch (e) {
    // 检测 quota 超限,触发全量清理后重试一次
    if (e instanceof DOMException && (e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED')) {
      await dbClearImages().catch(() => {})
      try {
        await tx(STORE_IMAGES, 'readwrite', (s) => s.put(blob, url))
      } catch {
        /* 仍失败则静默,降级直查后端 */
      }
    }
    /* 静默失败,降级直查后端 */
  }
}

export async function dbGetImage(url: string): Promise<Blob | null> {
  try {
    return (await tx(STORE_IMAGES, 'readonly', (s) => s.get(url))) ?? null
  } catch {
    return null
  }
}

export async function dbClearImages(): Promise<void> {
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const t = db.transaction(STORE_IMAGES, 'readwrite')
      t.objectStore(STORE_IMAGES).clear()
      t.oncomplete = () => resolve()
      t.onerror = () => reject(t.error)
      t.onabort = () => reject(t.error)
    })
  } catch {
    /* 静默 */
  }
}

/**
 * 清理不再使用的图片 Blob。
 * 菜品下架或图片 URL 变更后(?v= 版本参数改变),旧 Blob 会残留在 IndexedDB 中,
 * 长期运行会撑爆磁盘。每次刷新菜品后调用,删除不在 usedUrls 中的孤儿 Blob。
 */
export async function dbClearUnusedImages(usedUrls: Set<string>): Promise<void> {
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const t = db.transaction(STORE_IMAGES, 'readwrite')
      const store = t.objectStore(STORE_IMAGES)
      const req = store.openCursor()
      req.onsuccess = () => {
        const cursor = req.result
        if (cursor) {
          const key = cursor.key as string
          if (!usedUrls.has(key)) {
            cursor.delete()
          }
          cursor.continue()
        }
      }
      t.oncomplete = () => resolve()
      t.onerror = () => reject(t.error)
      t.onabort = () => reject(t.error)
    })
  } catch {
    /* 静默 */
  }
}

/* ============ 菜单数据缓存 ============ */

export async function dbPutMenu(key: string, menu: unknown): Promise<void> {
  try {
    await tx(STORE_MENUS, 'readwrite', (s) => s.put(menu, key))
  } catch {
    /* 静默 */
  }
}

export async function dbGetMenu<T = unknown>(key: string): Promise<T | null> {
  try {
    return (await tx(STORE_MENUS, 'readonly', (s) => s.get(key))) ?? null
  } catch {
    return null
  }
}

/** 精确删除指定 key 的菜单缓存(不影响其他日期) */
export async function dbDeleteMenu(key: string): Promise<void> {
  try {
    await tx(STORE_MENUS, 'readwrite', (s) => s.delete(key))
  } catch {
    /* 静默 */
  }
}

export async function dbClearMenus(): Promise<void> {
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const t = db.transaction(STORE_MENUS, 'readwrite')
      t.objectStore(STORE_MENUS).clear()
      t.oncomplete = () => resolve()
      t.onerror = () => reject(t.error)
      t.onabort = () => reject(t.error)
    })
  } catch {
    /* 静默 */
  }
}

/* ============ 员工数据缓存 ============ */

/** 缓存的员工信息(精简版,仅刷卡识别所需) */
export interface CachedEmployee {
  id: number
  cardNo: string
  name: string
  /** 头像 URL(可选,与 store Employee.avatar 兼容) */
  avatar?: string
  departmentId: number | null
  /** 部门名称(无部门时为空字符串,与 store Employee.departmentName 兼容) */
  departmentName: string
  balance: number
  status: number
  storeId: number
}

/** 批量写入员工(全量覆盖) */
export async function dbPutEmployees(employees: CachedEmployee[]): Promise<void> {
  if (employees.length === 0) return
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE_EMPLOYEES, 'readwrite')
    const s = t.objectStore(STORE_EMPLOYEES)
    s.clear() // 全量覆盖:先清空再写入
    for (const e of employees) s.put(e)
    t.oncomplete = () => resolve()
    t.onerror = () => reject(t.error)
    t.onabort = () => reject(t.error)
  })
}

/** 按 cardNo 查员工(刷卡时用,毫秒级) */
export async function dbGetEmployeeByCardNo(cardNo: string): Promise<CachedEmployee | null> {
  try {
    return (await tx(STORE_EMPLOYEES, 'readonly', (s) => s.get(cardNo))) ?? null
  } catch {
    return null
  }
}

/** 获取所有缓存员工(头像预加载用) */
export async function dbGetAllEmployees(): Promise<CachedEmployee[]> {
  try {
    return (await tx(STORE_EMPLOYEES, 'readonly', (s) => s.getAll())) ?? []
  } catch {
    return []
  }
}

/** 清空员工缓存(切换门店/解绑时调用) */
export async function dbClearEmployees(): Promise<void> {
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const t = db.transaction(STORE_EMPLOYEES, 'readwrite')
      t.objectStore(STORE_EMPLOYEES).clear()
      t.oncomplete = () => resolve()
      t.onerror = () => reject(t.error)
      t.onabort = () => reject(t.error)
    })
  } catch {
    /* 静默 */
  }
}
