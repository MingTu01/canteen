/**
 * v-osk 指令:触屏设备点击输入框时自动唤起 Windows 屏幕键盘。
 *
 * 用法:给任意 <input> 加 v-osk 即可:
 *   <input v-osk v-model="keyword" ... />
 *
 * 触发条件(全部满足才唤起):
 *   1. Python Shell 环境(浏览器开发模式不唤起)
 *   2. config.json 的 osk_mode 不是 'off'(设置页可关)
 *   3. 最近一次用户输入是触摸(触摸屏点按;鼠标/物理键盘操作不唤起,
 *      避免接了物理键盘的设备反复弹键盘)
 *
 * 输入框失焦(blur)时自动关闭屏幕键盘(未运行时为无害空操作)。
 */
import type { Directive } from 'vue'
import { showOsk, hideOsk, getRuntimeConfig } from '@/api/shellApi'

/** 最近一次输入是否来自触摸(全局单例,捕获阶段最早感知) */
let lastInputWasTouch = false

if (typeof window !== 'undefined') {
  window.addEventListener('touchstart', () => { lastInputWasTouch = true }, {
    capture: true, passive: true,
  })
  window.addEventListener('mousedown', () => { lastInputWasTouch = false }, { capture: true })
  window.addEventListener('keydown', () => { lastInputWasTouch = false }, { capture: true })
}

/** 是否允许唤起(每次 focus 现查 config,本地回环请求开销可忽略且配置实时) */
const oskAllowed = async (): Promise<boolean> => {
  const cfg = await getRuntimeConfig()
  return !!cfg && cfg.osk_mode !== 'off'
}

const onFocusIn = async (el: HTMLElement) => {
  if (!lastInputWasTouch) return
  if (await oskAllowed()) {
    showOsk()
    el.dataset.oskShown = '1'
  }
}

const onFocusOut = (el: HTMLElement) => {
  if (el.dataset.oskShown === '1') {
    el.dataset.oskShown = ''
    hideOsk()
  }
}

export const vOsk: Directive<HTMLElement> = {
  mounted(el) {
    el.addEventListener('focusin', () => onFocusIn(el))
    el.addEventListener('focusout', () => onFocusOut(el))
  },
}
