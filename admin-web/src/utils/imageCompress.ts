/**
 * 图片压缩工具:用 canvas 把图片压缩到 200k 左右。
 *
 * 策略:
 * - 大图(>200k)逐步降低 quality 直至 <=200k
 * - 保持宽高比,最大尺寸 800px(菜品图最大展示场景 H5 详情 ~400px,800px 仍 2 倍冗余;管理后台预览够用)
 * - PNG / 含透明通道的图片保持 PNG 格式(保留透明通道,logo/图标必需);JPEG/WebP 用 JPEG 压缩
 *
 * 用法:
 *   const file = event.target.files[0]
 *   const compressed = await compressImage(file, 200)
 *   const { url } = await fileApi.uploadImage(compressed)
 */

const TARGET_SIZE_KB = 200
const MAX_DIMENSION = 800
const MIN_QUALITY = 0.5

/**
 * 压缩图片到目标大小(默认 200KB)
 * @param file 原始 File 对象
 * @param targetKB 目标大小 KB(默认 200)
 * @returns 压缩后的 File 对象(保留原文件名)
 */
export async function compressImage(file: File, targetKB: number = TARGET_SIZE_KB): Promise<File> {
  // 非图片直接返回(部分浏览器对特殊格式可能不报告 type,也放行让后端校验)
  if (file.type && !file.type.startsWith('image/')) {
    return file
  }
  // 已经小于目标大小,直接返回(无需压缩)
  if (file.size <= targetKB * 1024) {
    return file
  }

  // PNG 检测:同时检查 MIME 类型和文件扩展名(部分系统可能不报告正确的 MIME)
  const isPngByMime = file.type === 'image/png'
  const isPngByExt = /\.png$/i.test(file.name)
  let isPng = isPngByMime || isPngByExt

  const img = await loadImage(file)
  const { width, height } = clampDimension(img.width, img.height, MAX_DIMENSION)

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    // canvas 不可用,返回原文件
    return file
  }
  ctx.drawImage(img, 0, 0, width, height)

  // 检测 canvas 中是否真正存在透明像素(alpha < 255)
  // 如果有,强制使用 PNG 输出以保留透明通道(即使原文件扩展名不是 .png)
  if (!isPng && hasTransparency(ctx, width, height)) {
    isPng = true
  }

  const outputType = isPng ? 'image/png' : 'image/jpeg'

  let blob: Blob | null
  if (isPng) {
    // PNG 无损压缩(仅缩放尺寸,不降质量),保留透明通道
    blob = await canvasToBlob(canvas, 'image/png')
  } else {
    // JPEG 逐步降低 quality 直到 <= 目标大小
    let quality = 0.9
    blob = await canvasToBlob(canvas, 'image/jpeg', quality)
    while (blob && blob.size > targetKB * 1024 && quality > MIN_QUALITY) {
      quality -= 0.1
      blob = await canvasToBlob(canvas, 'image/jpeg', quality)
    }
  }

  if (!blob) {
    return file
  }

  // 如果压缩后反而比原文件大,返回原文件
  if (blob.size >= file.size) {
    return file
  }

  const ext = isPng ? '.png' : '.jpg'
  const baseName = file.name.replace(/\.[^.]+$/, '') || 'image'
  return new File([blob], baseName + ext, { type: outputType, lastModified: Date.now() })
}

/**
 * 检测 canvas 中是否存在透明像素(alpha < 255)。
 * 用于判断图片是否真正包含透明通道,决定是否需要 PNG 格式保留透明。
 * 采用采样检测,避免全量扫描大图导致性能问题。
 */
function hasTransparency(ctx: CanvasRenderingContext2D, width: number, height: number): boolean {
  try {
    const imageData = ctx.getImageData(0, 0, width, height)
    const data = imageData.data
    // 采样步长:最多检测 ~5000 个像素点,保证性能
    const totalPixels = width * height
    const sampleStep = Math.max(1, Math.floor(Math.sqrt(totalPixels / 5000)))
    for (let y = 0; y < height; y += sampleStep) {
      for (let x = 0; x < width; x += sampleStep) {
        const alpha = data[(y * width + x) * 4 + 3]
        if (alpha < 255) {
          return true
        }
      }
    }
  } catch {
    // getImageData 可能因跨域限制失败,保守返回 false
  }
  return false
}

/** 加载 File 为 HTMLImageElement */
function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = (e) => {
      URL.revokeObjectURL(url)
      reject(e)
    }
    img.src = url
  })
}

/** 限制最大尺寸,保持宽高比 */
function clampDimension(width: number, height: number, max: number): { width: number; height: number } {
  if (width <= max && height <= max) {
    return { width, height }
  }
  const ratio = Math.min(max / width, max / height)
  return {
    width: Math.round(width * ratio),
    height: Math.round(height * ratio),
  }
}

/** canvas 转 Blob */
function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality)
  })
}
