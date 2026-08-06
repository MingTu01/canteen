<script setup lang="ts">
interface Props {
  title?: string
  description?: string
}

defineProps<Props>()
</script>

<template>
  <div class="mx-auto w-full max-w-[1600px] px-4 py-6 sm:px-6 lg:px-8" role="main" :aria-label="title">
    <header
      v-if="title || description || $slots.actions"
      class="mb-6 flex flex-wrap items-center justify-between gap-4"
    >
      <div class="min-w-0">
        <h1 v-if="title" class="text-2xl font-bold tracking-tight text-text">
          {{ title }}
        </h1>
        <p v-if="description" class="mt-1.5 text-sm text-text-secondary">
          {{ description }}
        </p>
      </div>
      <div v-if="$slots.actions" class="flex shrink-0 flex-wrap items-center gap-3">
        <slot name="actions" />
      </div>
    </header>
    <slot />
  </div>
</template>
