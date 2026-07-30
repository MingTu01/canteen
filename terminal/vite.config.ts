import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

/**
 * PostCSS 插件:为 QtWebEngine(Chrome 83)添加 CSS 兼容性
 *
 * Chrome 83 的两个关键问题:
 * 1. `inset`(Chrome 87+ 支持)→ 补 top/right/bottom/left
 * 2. flex `gap`(Chrome 84+ 支持)→ Chrome 83 的 getComputedStyle 会误报支持,
 *    但实际不渲染间距。因此不能用 @supports 检测,必须无条件用 margin 替代:
 *    把 `gap: Xpx` 转成 `> * + * { margin-top/left: Xpx }`,并删除原 gap 声明,
 *    避免现代浏览器出现双重间距。
 */
function chrome83Compat(): { postcssPlugin: string; Declaration: (decl: any) => void; RuleExit: (rule: any, helpers: any) => void } {
  return {
    postcssPlugin: 'chrome83-compat',
    Declaration(decl: any) {
      // inset: X → top/right/bottom/left: X(Chrome 83 不支持 inset 简写)
      if (decl.prop === 'inset') {
        const v = decl.value
        decl.cloneBefore({ prop: 'top', value: v })
        decl.cloneBefore({ prop: 'right', value: v })
        decl.cloneBefore({ prop: 'bottom', value: v })
        decl.cloneBefore({ prop: 'left', value: v })
      }
    },
    RuleExit(rule: any, { postcss }: any) {
      if (rule.type !== 'rule') return

      const decls = rule.nodes.filter((n: any) => n.type === 'decl')
      const displayDecl = decls.find((n: any) => n.prop === 'display')
      if (!displayDecl) return
      if (displayDecl.value !== 'flex' && displayDecl.value !== 'inline-flex') return

      const gapDecl = decls.find((n: any) => n.prop === 'gap')
      if (!gapDecl) return

      // 只处理像素值的 gap
      const pxMatch = gapDecl.value.match(/^([\d.]+)px$/)
      if (!pxMatch) return
      const px = pxMatch[1]

      // 判断 flex-direction(默认 row)
      const dirDecl = decls.find((n: any) => n.prop === 'flex-direction')
      const isColumn = dirDecl && (dirDecl.value === 'column' || dirDecl.value === 'column-reverse')
      const marginProp = isColumn ? 'margin-top' : 'margin-left'

      // 生成 fallback 规则:原选择器 > * + * { margin-top/left: Xpx }
      const fallbackRule = postcss.rule({ selector: `${rule.selector} > * + *` })
      fallbackRule.append(postcss.decl({ prop: marginProp, value: `${px}px` }))
      rule.after(fallbackRule)

      // 删除原 gap 声明(避免现代浏览器 margin+gap 双重间距)
      gapDecl.remove()
    },
  }
}

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  css: {
    postcss: {
      plugins: [chrome83Compat()],
    },
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5175,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
