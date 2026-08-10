/**
 * 下载中心 composable(纯前端)
 *
 * 设计:
 *   - 主路径:从 jsdelivr/gitmirror 加速拉取仓库根目录 VERSIONS.json,读取 terminal.version,
 *             拼接 GitHub Releases 下载 URL。VERSIONS.json 每次发版都会更新,实现自动跟随。
 *             此路径不依赖 api.github.com(国内浏览器基本不通),走 jsdelivr CDN 稳定可达。
 *   - 备路径:fetch GitHub Releases API(外网环境可用时获取更完整信息:发布时间/说明等)。
 *   - 降级:API 与 raw 都失败时,回退到写死的备用链接,保证弹窗永远有内容可下载。
 *   - 下载用 <a> 跳转,不 fetch blob,简单可靠,不会被浏览器拦截。
 *
 * 说明:fetch 由浏览器发起,用的是打开页面者的网络(代理/加速器均生效)。
 */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'

// GitHub 仓库
const REPO = 'MingTu01/canteen'

// VERSIONS.json 的 raw 加速地址(主路径,按优先级)
// jsdelivr 全球 CDN 最稳定;gh-proxy 代理 raw 兜底;直连最后
const RAW_VERSIONS_URLS = [
  `https://cdn.jsdelivr.net/gh/${REPO}@main/VERSIONS.json`,
  `https://gh-proxy.com/https://raw.githubusercontent.com/${REPO}/main/VERSIONS.json`,
  `https://raw.githubusercontent.com/${REPO}/main/VERSIONS.json`,
]

// GitHub API 地址(备路径,外网环境可用)
const RELEASES_API_URLS = [
  `https://api.github.com/repos/${REPO}/releases`,
  `https://gh-proxy.com/https://api.github.com/repos/${REPO}/releases`,
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
  /** 资产名(如 CanteenTerminal-Setup-1.0.18.exe) */
  name: string
  /** 版本号 */
  version: string
  /** 原始下载地址(GitHub 直链) */
  url: string
  /** 发布时间(ISO) */
  publishedAt: string
  /** 发布说明 */
  notes: string
  /** 数据来源:raw=VERSIONS.json / api=GitHub API / fallback=写死备用 */
  source: 'raw' | 'api' | 'fallback'
}

// ===== 降级备用链接(raw 和 API 都失败时用,发布新版本时更新这里) =====
const FALLBACK_CARD_HELPER: DownloadItem = {
  name: 'CanteenCardHelper-Setup-1.2.0.exe',
  version: '1.2.0',
  url: 'https://github.com/MingTu01/canteen/releases/download/card-helper-v1.2.0/CanteenCardHelper-Setup-1.2.0.exe',
  notes: '用于 CH375/CH372 读卡器刷卡识别,管理员录入员工卡号时需要安装。通用模拟键盘/HID 读卡器无需读卡助手。',
  publishedAt: '',
  source: 'fallback',
}

const FALLBACK_TERMINAL: DownloadItem = {
  name: 'CanteenTerminal-Setup-1.0.18.exe',
  version: '1.0.18',
  url: 'https://github.com/MingTu01/canteen/releases/download/1.0.18/CanteenTerminal-Setup-1.0.18.exe',
  notes: '食堂刷卡取餐终端(Windows),支持读卡器/摄像头扫码,安装到 X86 一体机。',
  publishedAt: '',
  source: 'fallback',
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

  /**
   * 主路径:从 raw 加速拉取 VERSIONS.json,读 terminal.version 拼下载 URL。
   * 优点:不依赖 api.github.com(国内不通),jsdelivr CDN 稳定;VERSIONS.json 每次发版自动更新。
   * 局限:只能拿到版本号,拿不到发布时间/说明;cardHelper 不在 VERSIONS.json 里(回退到 API/备用)。
   */
  async function fetchFromRaw(): Promise<DownloadItem | null> {
    for (const rawUrl of RAW_VERSIONS_URLS) {
      try {
        const ctrl = new AbortController()
        const timer = setTimeout(() => ctrl.abort(), 10000)
        const res = await fetch(rawUrl, {
          signal: ctrl.signal,
          headers: { Accept: 'application/json' },
        })
        clearTimeout(timer)
        if (!res.ok) continue
        const data = await res.json()
        const ver = data?.terminal?.version
        if (!ver) continue
        // tag 与 version 一致(从 1.0.18 起统一无 v 前缀)
        const fileName = `CanteenTerminal-Setup-${ver}.exe`
        return {
          name: fileName,
          version: ver,
          url: `https://github.com/${REPO}/releases/download/${ver}/${fileName}`,
          publishedAt: '',
          notes: '食堂刷卡取餐终端(Windows),支持读卡器/摄像头扫码,安装到 X86 一体机。',
          source: 'raw',
        }
      } catch {
        // 当前地址失败,尝试下一个
      }
    }
    return null
  }

  /** 备路径:从 GitHub Releases API 获取(外网环境可用,能拿到发布时间/说明) */
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
            source: 'api',
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
            source: 'api',
          }
        }
      }

      if (cardHelperItem && terminalItem) break
    }

    return { cardHelperItem, terminalItem }
  }

  /** 加载最新下载信息(raw 主路径 → API 备路径 → 备用链接降级) */
  async function load() {
    loading.value = true
    try {
      // 1. 主路径:raw VERSIONS.json 拿终端版本(最可靠,国内可用)
      const rawTerminal = await fetchFromRaw()

      // 2. 备路径:GitHub API(能同时拿读卡助手 + 发布时间/说明,外网环境可用)
      const releases = await fetchReleases()
      let apiCardHelper: DownloadItem | null = null
      let apiTerminal: DownloadItem | null = null
      if (releases.length > 0) {
        const found = findAssets(releases)
        apiCardHelper = found.cardHelperItem
        apiTerminal = found.terminalItem
      }

      // 3. 合并:raw 优先(raw 拿到的终端版本最新),API 补充读卡助手和发布时间
      // 终端:raw 优先(API 可能因缓存延迟),都没有用备用
      terminal.value = rawTerminal || apiTerminal || FALLBACK_TERMINAL
      // 读卡助手:VERSIONS.json 没有,只能靠 API 或备用
      cardHelper.value = apiCardHelper || FALLBACK_CARD_HELPER
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
