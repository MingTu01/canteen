/**
 * 屏幕适配 composable
 *
 * 终端设备分辨率多样:
 * - 1920×1080(标准横屏)
 * - 1280×720(旧设备横屏)
 * - 1080×1920(竖屏)
 * - 其他非标准分辨率
 *
 * 提供响应式的方向/尺寸/缩放因子,供页面动态调整布局。
 */

import { ref, onMounted, onUnmounted, computed } from 'vue'

export type Orientation = 'landscape' | 'portrait'
export type ScreenSize = 'small' | 'medium' | 'large' // <720 / 720-1080 / >1080

export interface ScreenState {
  width: number
  height: number
  orientation: Orientation
  size: ScreenSize
  /** 缩放因子:1080p=1, 720p=0.75, 竖屏另算 */
  scale: number
}

const state = ref<ScreenState>({
  width: 1920,
  height: 1080,
  orientation: 'landscape',
  size: 'large',
  scale: 1,
})

const update = () => {
  const w = window.innerWidth
  const h = window.innerHeight
  const orientation: Orientation = w >= h ? 'landscape' : 'portrait'
  // size 基于较短边(横屏看高度,竖屏看宽度)
  const short = Math.min(w, h)
  let size: ScreenSize = 'medium'
  if (short < 720) size = 'small'
  else if (short >= 1080) size = 'large'
  // scale 基于较短边相对 1080 的比例,夹在 0.6~1.2 之间
  const scale = Math.max(0.6, Math.min(1.2, short / 1080))
  state.value = { width: w, height: h, orientation, size, scale }
}

let listenerCount = 0
let resizeHandler: (() => void) | null = null

/** 屏幕适配 composable */
export function useScreen() {
  onMounted(() => {
    if (listenerCount === 0) {
      resizeHandler = update
      window.addEventListener('resize', resizeHandler)
      update() // 初始化
    }
    listenerCount++
  })

  onUnmounted(() => {
    listenerCount--
    if (listenerCount === 0 && resizeHandler) {
      window.removeEventListener('resize', resizeHandler)
      resizeHandler = null
    }
  })

  return {
    screen: computed(() => state.value),
    isLandscape: computed(() => state.value.orientation === 'landscape'),
    isPortrait: computed(() => state.value.orientation === 'portrait'),
    isSmall: computed(() => state.value.size === 'small'),
    scale: computed(() => state.value.scale),
  }
}

/** 不带生命周期的纯读取(在非组件场景使用) */
export function getScreenState(): ScreenState {
  return state.value
}
