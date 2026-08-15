import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'
import { readFileSync, writeFileSync, mkdirSync } from 'fs'

// 读取 package.json 的 version
const pkgVersion = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf-8'),
).version || '0.0.0'

/** 构建后生成 version.json 到 dist 根目录(供前端版本检测拉取) */
function generateVersionJson() {
  return {
    name: 'generate-version-json',
    closeBundle() {
      const outDir = resolve(__dirname, 'dist')
      try {
        mkdirSync(outDir, { recursive: true })
      } catch { /* dir exists */ }
      writeFileSync(
        resolve(outDir, 'version.json'),
        JSON.stringify(
          {
            version: pkgVersion,
            buildTime: new Date().toISOString(),
          },
          null,
          2,
        ),
      )
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue(), tailwindcss(), generateVersionJson()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        // /uploads/ 反向代理到后端,开发环境菜品图、Logo、Banner 等上传图片才能正常加载
        '/uploads': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    build: {
      rollupOptions: {
        output: {
          // 大依赖拆独立 chunk:避免全部打进首屏 index,提升缓存命中率;
          // echarts/xlsx 属首屏不需要的重依赖,配合路由懒加载不阻塞首屏
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
              if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
              if (id.includes('xlsx') || id.includes('cfb') || id.includes('codepage')) return 'xlsx'
              if (id.includes('lucide-vue-next')) return 'lucide'
              if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) return 'vue-vendor'
              return 'vendor'
            }
          },
        },
      },
    },
  }
})
