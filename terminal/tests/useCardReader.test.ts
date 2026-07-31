/**
 * useCardReader.ts 测试
 *
 * 覆盖本次 P3 修复:
 * 1. Tauri 事件监听分支已移除(不 import @tauri-apps/api/event)
 * 2. Python Shell 桥接(window.__onCardRead)正确初始化
 * 3. 卡号推送通过 __onCardRead 触发 handler
 * 4. USB HID 键盘降级方案正常工作
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useCardReader, destroyCardReader } from '../src/composables/useCardReader'

describe('useCardReader - Tauri 移除回归测试', () => {
  beforeEach(() => {
    // 清理全局状态
    delete (window as any).__onCardRead
    delete (window as any).__pythonShell
    delete (window as any).__TAURI_INTERNALS__
    // 重置模块级变量(通过 destroyCardReader)
    destroyCardReader()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    destroyCardReader()
  })

  it('window.__onCardRead 在挂载后被初始化', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: false })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    expect((window as any).__onCardRead).toBeDefined()
    expect(typeof (window as any).__onCardRead).toBe('function')
  })

  it('Python Shell 桥接:__onCardRead 调用时触发 handler', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: false })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    // 模拟 Python 读卡器推送卡号
    ;(window as any).__onCardRead('CARD12345')
    expect(handler).toHaveBeenCalledWith('CARD12345')
  })

  it('卡号前后空格被 trim', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: false })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    ;(window as any).__onCardRead('  CARD999  ')
    expect(handler).toHaveBeenCalledWith('CARD999')
  })

  it('空卡号不触发 handler', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: false })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    ;(window as any).__onCardRead('')
    expect(handler).not.toHaveBeenCalled()
  })

  it('USB HID 键盘降级:Enter 键提交缓冲区', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: true })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    // 模拟 USB HID 读卡器:逐字符输入 + Enter
    const dispatchKey = (key: string) => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key }))
    }
    dispatchKey('C')
    dispatchKey('A')
    dispatchKey('R')
    dispatchKey('D')
    dispatchKey('1')
    dispatchKey('Enter')

    expect(handler).toHaveBeenCalledWith('CARD1')
  })

  it('组件卸载后移除键盘监听(无内存泄漏)', async () => {
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: true })
        return () => h('div')
      },
    })
    const wrapper = mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    const removeSpy = vi.spyOn(window, 'removeEventListener')
    wrapper.unmount()

    expect(removeSpy).toHaveBeenCalledWith('keydown', expect.any(Function))
  })

  it('不尝试 import @tauri-apps/api/event(Tauri 已移除)', async () => {
    // 验证:即使 window.__TAURI_INTERNALS__ 存在,也不会触发 Tauri import
    ;(window as any).__TAURI_INTERNALS__ = {}
    const handler = vi.fn()
    const TestComp = defineComponent({
      setup() {
        useCardReader(handler, { listenKeyboard: false })
        return () => h('div')
      },
    })
    mount(TestComp)
    await new Promise(r => setTimeout(r, 0))

    // __onCardRead 仍应正常初始化(不因 __TAURI_INTERNALS__ 而走 Tauri 分支)
    expect((window as any).__onCardRead).toBeDefined()
    // handler 仍能正常被调用
    ;(window as any).__onCardRead('TEST_CARD')
    expect(handler).toHaveBeenCalledWith('TEST_CARD')
  })
})
