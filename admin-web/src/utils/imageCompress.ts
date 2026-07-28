/**
 * 图片压缩工具:用 canvas 把图片压缩到 200k 左右。
 *
 * 策略:
 * - 大图(>200k)逐步降低 quality 直至 <=200k
 * - 保持宽高比,最大尺寸 800px(菜品图最大展示场景 H5 详情 ~400px,800px 仍 2 倍冗余;管理后台预览够用)
 * - 输出 JPEG(jpeg 压缩率最高,适合照片;logo/图标用 PNG,但 200k 内的 PNG 直接原样返回)
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
  // 非图片直接返回
  if (!file.type.startsWith('image/')) {
    return file
  }
  // 已经小于目标大小,直接返回(无需压缩)
  if (file.size <= targetKB * 1024) {
    return file
  }

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

  // 逐步降低 quality 直到 <= 目标大小
  let quality = 0.9
  let blob = await canvasToBlob(canvas, 'image/jpeg', quality)
  while (blob && blob.size > targetKB * 1024 && quality > MIN_QUALITY) {
    quality -= 0.1
    blob = await canvasToBlob(canvas, 'image/jpeg', quality)
  }

  if (!blob) {
    return file
  }

  // 如果压缩后反而比原文件大,返回原文件
  if (blob.size >= file.size) {
    return file
  }

  // 保留原扩展名,但统一用 jpeg(压缩后一定是 jpeg)
  const ext = file.name.lastIndexOf('.') >= 0 ? file.name.substring(file.name.lastIndexOf('.')) : '.jpg'
  const newName = file.name.replace(/\.[^.]+$/, '') + (ext.toLowerCase() === '.png' ? '.jpg' : ext)
  return new File([blob], newName, { type: 'image/jpeg', lastModified: Date.now() })
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
function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality)
  })
}
