/**
 * ECharts 实例管理 composable
 *
 * 拆分自 Report.vue 中重复的 echarts 实例管理逻辑:
 *   - chartRef 模板引用
 *   - setOption 自动 init + setOption
 *   - resize 监听(挂载时注册,卸载时移除)
 *   - 卸载时 dispose 释放资源
 *
 * 用法:
 *   const { chartRef, setOption, resize } = useEcharts()
 *   // 模板: <div ref="chartRef" class="h-72 w-full"></div>
 *   // 设置选项:setOption({ ... })
 *
 * 多图表场景:每个图表独立调用一次 useEcharts()。
 */

import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'

export const useEcharts = () => {
  const chartRef = ref<HTMLDivElement | null>(null)
  let chart: echarts.ECharts | null = null

  /** 设置图表选项;首次调用自动 init,后续复用实例 */
  const setOption = (option: echarts.EChartsOption): void => {
    if (!chartRef.value) return
    if (!chart || chart.isDisposed()) {
      chart = echarts.init(chartRef.value)
    }
    chart.setOption(option)
  }

  /** 手动触发 resize(通常由 window resize 监听自动调用) */
  const resize = (): void => {
    chart?.resize()
  }

  onMounted(() => {
    window.addEventListener('resize', resize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    chart?.dispose()
    chart = null
  })

  return {
    chartRef,
    setOption,
    resize,
  }
}
