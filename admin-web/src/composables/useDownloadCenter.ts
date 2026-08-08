/**
 * 下载中心 composable
 *
 * 功能:
 *   1. 调用 GitHub Releases API 获取最新资产(读卡助手 / X86 终端)
 *   2. 自动叠加加速器前缀,多加速器逐个尝试,一个不行自动切换
 *   3. 通过 fetch + blob 触发浏览器下载(避免 window.open 被拦截)
 *
 * GitHub API 速率限制:未认证 60 次/小时(管理后台低频访问足够)
 */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'

// GitHub 仓库
const REPO = 'MingTu01/canteen'

// GitHub API 地址(获取所有 releases,用于按 tag 前缀筛选)
// 未认证的 API 请求加加速器可能返回 HTML 而非 JSON,因此 API 走直连 + 加速器双通道
const RELEASES_API_URLS = [
  `https://api.github.com/repos/${REPO}/releases`,
  `https://gh-proxy.com/https://api.github.com/repos/${REPO}/releases`,
  `https://ghfast.top/https://api.github.com/repos/${REPO}/releases`,
]

// GitHub 下载加速器前缀(按优先级,最后一个为空 = 直连)
// browser_download_url 形如 https://github.com/MingTu01/canteen/releases/download/xxx/yyy.exe
// 加速器拼接方式:prefix + original_url
const DOWNLOAD_ACCELERATORS = [
  'https://gh-proxy.com/',
  'https://ghfast.top/',
  'https://mirror.ghproxy.com/',
  '', // 直连兜底
]

// 资产名匹配规则
const CARD_HELPER_RE = /CanteenCardHelper-Setup-[\d.]+\.exe/i
const TERMINAL_RE = /CanteenTerminal-Setup-[\d.]+\.exe/i

export interface DownloadItem {
  /** 资产名(如 CanteenCardHelper-Setup-1.2.0.exe) */
  name: string
  /** 版本号(从资产名提取) */
  version: string
  /** 原始下载地址 */
  url: string
  /** 发布时间 */
  publishedAt: string
  /** 发布说明 */
  notes: string
}

export function useDownloadCenter() {
  const loading = ref(false)
  const cardHelper = ref<DownloadItem | null>(null)
  const terminal = ref<DownloadItem | null>(null)
  const downloading = ref(false)

  /** 从资产名提取版本号 */
  function extractVersion(name: string): string {
    const m = name.match(/Setup-([\d.]+)\.exe/i)
    return m ? m[1] : ''
  }

  /** 调用 GitHub API 获取 releases,按加速器逐个尝试 */
  async function fetchReleases(): Promise<any[]> {
    for (const apiUrl of RELEASES_API_URLS) {
      try {
        const ctrl = new AbortController()
        const timer = setTimeout(() => ctrl.abort(), 8000)
        const res = await fetch(apiUrl, {
          signal: ctrl.signal,
          headers: { Accept: 'application/vnd.github+json' },
        })
        clearTimeout(timer)
        if (!res.ok) continue
        const data = await res.json()
        if (Array.isArray(data) && data.length > 0) return data
      } catch {
        // 该加速器失败,尝试下一个
      }
    }
    return []
  }

  /** 从 releases 列表中查找读卡助手和终端的最新资产 */
  function findAssets(releases: any[]) {
    let cardHelperItem: DownloadItem | null = null
    let terminalItem: DownloadItem | null = null

    for (const rel of releases) {
      const assets = rel.assets || []
      const publishedAt = rel.published_at || rel.created_at || ''
      const notes = rel.body || ''

      if (!cardHelperItem) {
        const asset = assets.find((a: any) => CARD_HELPER_RE.test(a.name))
        if (asset) {
          cardHelperItem = {
            name: asset.name,
            version: extractVersion(asset.name),
            url: asset.browser_download_url,
            publishedAt,
            notes,
          }
        }
      }

      if (!terminalItem) {
        const asset = assets.find((a: any) => TERMINAL_RE.test(a.name))
        if (asset) {
          terminalItem = {
            name: asset.name,
            version: extractVersion(asset.name),
            url: asset.browser_download_url,
            publishedAt,
            notes,
          }
        }
      }

      // 两个都找到了就不用继续了
      if (cardHelperItem && terminalItem) break
    }

    return { cardHelperItem, terminalItem }
  }

  /** 加载最新下载信息 */
  async function load() {
    loading.value = true
    try {
      const releases = await fetchReleases()
      if (releases.length === 0) {
        ElMessage.warning('无法获取 GitHub Releases 信息(可能是网络问题)')
        return
      }
      const { cardHelperItem, terminalItem } = findAssets(releases)
      cardHelper.value = cardHelperItem
      terminal.value = terminalItem
    } catch (e) {
      ElMessage.error('获取下载信息失败')
    } finally {
      loading.value = false
    }
  }

  /** 下载文件:多加速器逐个尝试,fetch blob 方式触发下载 */
  async function download(item: DownloadItem | null) {
    if (!item) {
      ElMessage.warning('暂无可用下载地址')
      return
    }

    downloading.value = true
    try {
      // 构造所有加速器 URL
      const urls = DOWNLOAD_ACCELERATORS.map((prefix) => prefix + item.url)

      for (const url of urls) {
        try {
          const ctrl = new AbortController()
          const timer = setTimeout(() => ctrl.abort(), 30000) // 30 秒超时
          const res = await fetch(url, { signal: ctrl.signal })
          clearTimeout(timer)

          if (!res.ok) {
            continue
          }

          // 检查是否是文件流(避免加速器返回 HTML 错误页)
          const ct = res.headers.get('content-type') || ''
          if (ct.includes('text/html')) {
            continue
          }

          // 转为 blob 并触发下载
          const blob = await res.blob()
          if (blob.size < 1024) {
            continue
          }

          const blobUrl = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = blobUrl
          a.download = item.name
          document.body.appendChild(a)
          a.click()
          document.body.removeChild(a)
          setTimeout(() => URL.revokeObjectURL(blobUrl), 1000)

          ElMessage.success(`开始下载: ${item.name}`)
          return
        } catch {
          continue
        }
      }

      // 所有加速器都失败,最后用 window.open 直连
      ElMessage.warning('加速器下载失败,尝试直接打开 GitHub 页面...')
      window.open(item.url, '_blank')
    } finally {
      downloading.value = false
    }
  }

  return {
    loading,
    downloading,
    cardHelper,
    terminal,
    load,
    download,
  }
}
