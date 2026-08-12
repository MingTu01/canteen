<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, Loader2, X } from 'lucide-vue-next'
import { fileApi } from '@/api'
import { compressImage, compressImageLoose, compressImageDocument } from '@/utils/imageCompress'

/**
 * 图片上传组件
 *
 * 特性:
 * - 前端 canvas 压缩(默认 200k 左右/最大边长 800px)
 * - loose=true 时用宽松压缩(更大尺寸/更高画质,用于品牌/背景等大图,避免不清晰)
 * - document=true 时用 A4 文档压缩(1600px/1.5MB/q0.8,通知配图文字清晰)
 * - 支持预览与删除
 * - v-model 绑定 URL 字符串
 *
 * 用法:
 *   <ImageUploader v-model="form.logoUrl" label="企业 Logo" />
 *   <ImageUploader v-model="form.backgroundUrl" label="背景" loose />
 *   <ImageUploader v-model="form.imageUrl" label="通知配图" document />
 */
const props = withDefaults(
  defineProps<{
    modelValue?: string
    label?: string
    /** 提示文字 */
    hint?: string
    /** 预览图宽高(像素) */
    previewSize?: number
    /** 宽松压缩:用于品牌/背景等大图,保留更多细节(更大尺寸+更高画质) */
    loose?: boolean
    /** A4文档压缩:通知/公告配图专用,保证文字清晰(1600px/1.5MB/q0.8) */
    document?: boolean
  }>(),
  {
    modelValue: '',
    label: '图片',
    hint: '',
    previewSize: 100,
    loose: false,
    document: false,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const uploading = ref(false)
const inputRef = ref<HTMLInputElement>()

const handleFile = async (e: Event) => {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  uploading.value = true
  try {
    // 1. 前端 canvas 压缩(document=通知配图用A4文档压缩;loose=品牌大图用宽松压缩;默认200k)
    const compressed = props.document
      ? await compressImageDocument(file)
      : props.loose
        ? await compressImageLoose(file)
        : await compressImage(file)
    // 2. 上传到后端
    const result = await fileApi.uploadImage(compressed)
    emit('update:modelValue', result.url)
    ElMessage.success(`${props.label}上传成功(${Math.round(compressed.size / 1024)}KB)`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
    // 清空 input,允许重复上传同一文件
    if (inputRef.value) inputRef.value.value = ''
  }
}

const clearImage = () => {
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="flex items-start gap-3">
    <!-- 预览区 -->
    <div
      class="relative shrink-0 overflow-hidden rounded-lg border border-border bg-bg-tertiary"
      :style="{ width: previewSize + 'px', height: previewSize + 'px' }"
    >
      <img
        v-if="modelValue"
        :src="modelValue"
        :alt="label"
        class="h-full w-full object-cover"
      />
      <div
        v-else
        class="flex h-full w-full items-center justify-center text-text-muted"
      >
        <Upload class="h-6 w-6" />
      </div>
      <!-- 上传中遮罩 -->
      <div
        v-if="uploading"
        class="absolute inset-0 flex items-center justify-center bg-black/40"
      >
        <Loader2 class="h-5 w-5 animate-spin text-white" />
      </div>
      <!-- 删除按钮 -->
      <button
        v-if="modelValue && !uploading"
        type="button"
        class="absolute right-1 top-1 flex h-5 w-5 items-center justify-center rounded-full bg-black/60 text-white hover:bg-black/80"
        aria-label="删除图片"
        @click="clearImage"
      >
        <X class="h-3 w-3" />
      </button>
    </div>

    <!-- 操作与提示 -->
    <div class="flex-1">
      <button
        type="button"
        class="rounded-lg border border-border bg-card px-3 py-1.5 text-sm text-text-secondary transition-colors hover:bg-bg-tertiary hover:text-text"
        role="button"
        aria-label="上传图片"
        :disabled="uploading"
        @click="inputRef?.click()"
      >
        {{ uploading ? '上传中…' : `上传${label}` }}
      </button>
      <p v-if="hint" class="mt-1.5 text-xs text-text-muted">{{ hint }}</p>
      <p v-else class="mt-1.5 text-xs text-text-muted">
        {{ props.document ? 'A4文档压缩(1600px/1.5MB),文字清晰,支持 JPG/PNG/WebP' : props.loose ? '前端压缩(保留高画质),支持 JPG/PNG/WebP' : '自动压缩到 200KB 以内,支持 JPG/PNG/WebP' }}
      </p>
      <input
        ref="inputRef"
        type="file"
        accept="image/*"
        class="hidden"
        @change="handleFile"
      />
    </div>
  </div>
</template>
