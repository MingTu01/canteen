import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(false)

  const toggle = () => {
    isDark.value = !isDark.value
  }

  const apply = () => {
    document.documentElement.classList.toggle('dark', isDark.value)
  }

  watch(isDark, apply, { immediate: true })

  return { isDark, toggle, apply }
}, { persist: true })
