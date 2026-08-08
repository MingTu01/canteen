/**
 * 下载中心 composable(纯前端)
 *
 * 设计:
 *   - 从浏览器侧 fetch GitHub Releases API,自动获取最新版本号和下载链接(跟随文件更新)
 *   - API 失败时降级到写死的备用链接,保证弹窗永远有内容可下载
 *   - 下载用 <a> 跳转,不 fetch blob,简单可靠,不会被浏览器拦截
 *
 * 说明:fetch 由浏览器发起,用的是打开页面者的网络(代理/加速器均生效)。
 */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'

// GitHub 仓库
const REPO = 'MingTu01/canteen'

// GitHub API 地址(直连优先,加速器兜底)
const RELEASES_API_URLS = [
  `https://api.github.com/repos/${REPO}/releases`,
  `https://gh-proxy.com/https://api.github.com/repos/${REPO}/releases`,
  `https://ghfast.top/https://api.github.com/repos/${REPO}/releases`,
]

// GitHub 下载加速器前缀(按优先级,最后为空 = 直连)
// browser_download_url 形如 https://github.com/MingTu01/canteen/releases/download/<tag>/<file>
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
  /** 版本号 */
  version: string
  /** 原始下载地址(GitHub 直链) */
  url: string
  /** 发布时间(ISO) */
  publishedAt: string
  /** 发布说明 */
  notes: string
  /** 是否来自 API(用于显示"自动获取"还是"备用链接") */
  fromApi: boolean
}

// ===== 降级备用链接(API 失败时用,发布新版本时更新这里) =====
const FALLBACK_CARD_HELPER: DownloadItem = {
  name: 'CanteenCardHelper-Setup-1.2.0.exe',
  version: '1.2.0',
  url: 'https://github.com/MingTu01/canteen/releases/download/card-helper-v1.2.0/CanteenCardHelper-Setup-1.2.0.exe',
  notes: '用于 CH375/CH372 读卡器刷卡识别,管理员录入员工卡号时需要安装。通用模拟键盘/HID 读卡器无需读卡助手。',
  publishedAt: '',
  fromApi: false,
}

const FALLBACK_TERMINAL: DownloadItem = {
  name: 'CanteenTerminal-Setup-1.0.5.exe',
  version: '1.0.5',
  url: 'https://github.com/MingTu01/canteen/releases/download/v1.0.5/CanteenTerminal-Setup-1.0.5.exe',
  notes: '食堂刷卡取餐终端(Windows),支持读卡器/摄像头扫码,安装到 X86 一体机。',
  publishedAt: '',
  fromApi: false,
}
// =========================================================

export function useDownloadCenter() {
  const loading = ref(false)
  const cardHelper = ref<DownloadItem | null>(null)
  const terminal = ref<DownloadItem | null>(null)

  /** 从资产名提取版本号 */
  function extractVersion(name: string): string {
    const m = name.match(/Setup-([\d.]+)\.exe/i)
    return m ? m[1] : ''
  }

  /** 从浏览器侧 fetch GitHub API,逐个地址尝试 */
  async function fetchReleases(): Promise<any[]> {
    for (const apiUrl of RELEASES_API_URLS) {
      try {
        const ctrl = new AbortController()
        const timer = setTimeout(() => ctrl.abort(), 10000)
        const res = await fetch(apiUrl, {
          signal: ctrl.signal,
          headers: { Accept: 'application/vnd.github+json' },
        })
        clearTimeout(timer)
        if (!res.ok) continue
        const data = await res.json()
        if (Array.isArray(data) && data.length > 0) return data
      } catch {
        // 当前地址失败,尝试下一个
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
            fromApi: true,
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
            fromApi: true,
          }
        }
      }

      if (cardHelperItem && terminalItem) break
    }

    return { cardHelperItem, terminalItem }
  }

  /** 加载最新下载信息(API 失败时降级到备用链接) */
  async function load() {
    loading.value = true
    try {
      const releases = await fetchReleases()
      if (releases.length === 0) {
        // API 失败:降级到备用链接
        cardHelper.value = FALLBACK_CARD_HELPER
        terminal.value = FALLBACK_TERMINAL
        return
      }
      const { cardHelperItem, terminalItem } = findAssets(releases)
      cardHelper.value = cardHelperItem || FALLBACK_CARD_HELPER
      terminal.value = terminalItem || FALLBACK_TERMINAL
    } catch {
      // 异常:降级到备用链接
      cardHelper.value = FALLBACK_CARD_HELPER
      terminal.value = FALLBACK_TERMINAL
    } finally {
      loading.value = false
    }
  }

  /** 触发下载:用 <a> 跳转到加速器链接,浏览器直接下载 */
  function download(item: DownloadItem | null) {
    if (!item) {
      ElMessage.warning('暂无可用下载地址')
      return
    }
    const url = DOWNLOAD_ACCELERATORS[0] + item.url
    const a = document.createElement('a')
    a.href = url
    a.download = item.name
    a.target = '_blank'
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }

  /** 获取某个下载项的所有备用链接(加速器 + 直连) */
  function getAllUrls(item: DownloadItem | null): { label: string; url: string }[] {
    if (!item) return []
    return [
      { label: '加速器 1 (gh-proxy)', url: DOWNLOAD_ACCELERATORS[0] + item.url },
      { label: '加速器 2 (ghfast)', url: DOWNLOAD_ACCELERATORS[1] + item.url },
      { label: '加速器 3 (mirror.ghproxy)', url: DOWNLOAD_ACCELERATORS[2] + item.url },
      { label: 'GitHub 直连', url: item.url },
    ]
  }

  return {
    loading,
    cardHelper,
    terminal,
    load,
    download,
    getAllUrls,
  }
}
