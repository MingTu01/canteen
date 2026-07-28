import { get } from './index'
import { rawAxios } from './index'
import type { AxiosResponse } from 'axios'
import type { PublicStore, Branding, ApiResponse } from './types'

/**
 * 门店公开 API
 * 后端:StoreController
 * - publicList:免 token,登录页选择食堂
 * - branding:免 token,带 ETag 304 缓存
 */

/** 获取营业中的食堂列表(公开接口,字段子集,无敏感字段) */
export function getPublicStores(): Promise<PublicStore[]> {
  return get<PublicStore[]>('/store/public-list')
}

/**
 * 获取食堂品牌信息(logo/图片/H5 banner/简介)。
 * 带 ETag 304 缓存:数据未变时后端返回 304(零带宽)。
 *
 * 通过 _raw 标记绕过业务拦截器,返回完整 AxiosResponse,
 * 供 branding store 读取 ETag header 与 304 状态。
 *
 * 注意:304 响应无 body,res.data 为 undefined。
 */
export function getBranding(
  storeId: number,
  etag?: string | null,
): Promise<AxiosResponse<ApiResponse<Branding> | undefined>> {
  const headers: Record<string, string> = {}
  if (etag) headers['If-None-Match'] = etag
  return rawAxios.get<ApiResponse<Branding>, AxiosResponse<ApiResponse<Branding> | undefined>>(
    `/store/${storeId}/branding`,
    {
      headers,
      // 接受 304 作为成功状态(axios 默认只接受 2xx)
      validateStatus: (s) => (s >= 200 && s < 300) || s === 304,
      _raw: true,
    },
  )
}
