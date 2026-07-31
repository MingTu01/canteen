/**
 * shellApi.ts 测试
 *
 * 覆盖本次 P3 修复:
 * 1. Tauri 代码路径已完全移除(ShellType 仅 'python' | 'browser')
 * 2. detectShell() 在无 __pythonShell 时返回 'browser'
 * 3. detectShell() 在有 __pythonShell 时返回 'python'
 * 4. 各 API 函数在浏览器环境正确降级(不调用 Tauri)
 * 5. Python Shell 环境正确调用 fetch /__api__/xxx
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  detectShell,
  getServerUrl,
  getRuntimeConfig,
  setRuntimeConfig,
  switchToConfigMode,
  callShell,
  quitApp,
  restartCardReader,
} from '../src/api/shellApi'

describe('shellApi - Tauri 移除回归测试', () => {
  beforeEach(() => {
    // 每个测试前清理 window 上的标记
    delete (window as any).__pythonShell
    delete (window as any).__TAURI_INTERNALS__
    delete (window as any).__TAURI__
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('detectShell', () => {
    it('无 __pythonShell 时返回 browser', () => {
      expect(detectShell()).toBe('browser')
    })

    it('有 __pythonShell=true 时返回 python', () => {
      ;(window as any).__pythonShell = true
      expect(detectShell()).toBe('python')
    })

    it('有 __TAURI_INTERNALS__ 时仍返回 browser(Tauri 已移除)', () => {
      ;(window as any).__TAURI_INTERNALS__ = {}
      expect(detectShell()).toBe('browser')
    })

    it('有 __TAURI__ 时仍返回 browser(Tauri 已移除)', () => {
      ;(window as any).__TAURI__ = {}
      expect(detectShell()).toBe('browser')
    })
  })

  describe('getServerUrl', () => {
    it('浏览器环境返回空字符串(不尝试 import @tauri-apps/api)', async () => {
      const result = await getServerUrl()
      expect(result).toBe('')
    })

    it('Python 环境通过 fetch /__api__/server_url 获取地址', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({
        json: () => Promise.resolve({ server_url: 'http://192.168.1.100:8080' }),
      })
      vi.stubGlobal('fetch', mockFetch)

      const result = await getServerUrl()
      expect(result).toBe('http://192.168.1.100:8080')
      expect(mockFetch).toHaveBeenCalledWith('/__api__/server_url')
    })

    it('fetch 失败时返回空字符串', async () => {
      ;(window as any).__pythonShell = true
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
      const result = await getServerUrl()
      expect(result).toBe('')
    })
  })

  describe('getRuntimeConfig', () => {
    it('浏览器环境返回 null', async () => {
      const result = await getRuntimeConfig()
      expect(result).toBeNull()
    })

    it('Python 环境返回配置对象', async () => {
      ;(window as any).__pythonShell = true
      const mockConfig = {
        server_url: 'http://localhost:8080',
        window_mode: 'fullscreen' as const,
        card_interval: 2.0,
        idle_timeout: 30,
      }
      const mockFetch = vi.fn().mockResolvedValue({
        json: () => Promise.resolve({ ok: true, config: mockConfig }),
      })
      vi.stubGlobal('fetch', mockFetch)

      const result = await getRuntimeConfig()
      expect(result).toEqual(mockConfig)
    })
  })

  describe('setRuntimeConfig', () => {
    it('浏览器环境返回 false', async () => {
      const result = await setRuntimeConfig({ card_interval: 3 })
      expect(result).toBe(false)
    })

    it('Python 环境通过 POST 更新配置', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({
        json: () => Promise.resolve({ ok: true }),
      })
      vi.stubGlobal('fetch', mockFetch)

      const result = await setRuntimeConfig({ window_mode: 'windowed' })
      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith('/__api__/set_config', expect.objectContaining({
        method: 'POST',
      }))
    })
  })

  describe('switchToConfigMode', () => {
    it('浏览器环境不报错(静默跳过)', async () => {
      await expect(switchToConfigMode()).resolves.not.toThrow()
    })

    it('Python 环境调用 /__api__/switch_to_config', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({})
      vi.stubGlobal('fetch', mockFetch)

      await switchToConfigMode()
      expect(mockFetch).toHaveBeenCalledWith('/__api__/switch_to_config', { method: 'POST' })
    })
  })

  describe('callShell', () => {
    it('浏览器环境直接返回(不 fetch)', async () => {
      const mockFetch = vi.fn()
      vi.stubGlobal('fetch', mockFetch)
      await callShell('switch_to_fullscreen')
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('Python 环境调用指定方法端点', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({})
      vi.stubGlobal('fetch', mockFetch)

      await callShell('switch_to_fullscreen')
      expect(mockFetch).toHaveBeenCalledWith('/__api__/switch_to_fullscreen', { method: 'POST' })
    })
  })

  describe('quitApp', () => {
    it('浏览器环境调用 window.close()', async () => {
      const closeSpy = vi.spyOn(window, 'close').mockImplementation(() => {})
      await quitApp()
      expect(closeSpy).toHaveBeenCalled()
    })

    it('Python 环境调用 /__api__/quit', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({})
      vi.stubGlobal('fetch', mockFetch)
      vi.spyOn(window, 'close').mockImplementation(() => {})

      await quitApp()
      expect(mockFetch).toHaveBeenCalledWith('/__api__/quit', { method: 'POST' })
    })
  })

  describe('restartCardReader', () => {
    it('浏览器环境返回 false', async () => {
      const result = await restartCardReader()
      expect(result).toBe(false)
    })

    it('Python 环境返回读卡器状态', async () => {
      ;(window as any).__pythonShell = true
      const mockFetch = vi.fn().mockResolvedValue({
        json: () => Promise.resolve({ running: true }),
      })
      vi.stubGlobal('fetch', mockFetch)

      const result = await restartCardReader()
      expect(result).toBe(true)
    })
  })
})
