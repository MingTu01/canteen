/** 将后端返回的数组或分页对象统一转为数组 */
export function normalizeList<T>(data: T[] | { records?: T[] } | unknown): T[] {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object' && 'records' in data) {
    return (data as { records?: T[] }).records ?? []
  }
  return []
}
