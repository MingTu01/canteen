import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { readFileSync } from 'fs'
import postcsspxtoviewport from 'postcss-px-to-viewport'
import { viteVConsole } from 'vite-plugin-vconsole'

// 读取 package.json 的 version,作为全局版本号注入
const pkgVersion = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf-8'),
).version || '0.0.0'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const isDev = mode === 'development'

  return {
    plugins: [
      vue(),
      // 仅 dev 环境启用 vconsole,便于移动端调试
      isDev &&
        viteVConsole({
          entry: resolve(__dirname, 'src/main.ts'),
          localEnabled: isDev,
          enabled: isDev,
          config: {
            maxLogNumber: 1000,
            theme: 'dark',
          },
        }),
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    define: {
      __APP_VERSION__: JSON.stringify(pkgVersion),
    },
    server: {
      host: '0.0.0.0',
      port: 5174,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        // /uploads/ 反向代理到后端,开发环境菜品图片等静态资源才能正常加载
        '/uploads': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    css: {
      preprocessorOptions: {
        scss: {
          // 现代 sass API,避免 legacy 警告
          api: 'modern-compiler',
        },
      },
    },
    postcss: {
      plugins: [
        // 375 设计稿 → 视口适配,Vant 4 基于 375 设计稿,统一转换
        postcsspxtoviewport({
          viewportWidth: 375,
          unitPrecision: 5,
          viewportUnit: 'vw',
          selectorBlackList: ['.ignore-vw'],
          minPixelValue: 1,
          mediaQuery: false,
        }),
      ],
    },
    build: {
      target: 'es2015',
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1000,
    },
  }
})
