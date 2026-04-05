<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { useTheme } from '../composables/useTheme'

const router = useRouter()
const { isDark } = useTheme()
</script>

<template>
  <div class="flex items-center justify-center min-h-[60vh]">
    <div class="text-center px-6">
      <ExclamationTriangleIcon class="w-16 h-16 mx-auto mb-4" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
      <h1 class="text-4xl font-bold mb-2" :class="isDark ? 'text-gray-200' : 'text-gray-800'">404</h1>
      <p class="text-lg mb-6" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Page not found</p>
      <button
        @click="router.push({ name: 'projects' })"
        class="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white transition-colors cursor-pointer"
        :class="isDark ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-emerald-600 hover:bg-emerald-700'"
      >
        Go to Projects
      </button>
    </div>
  </div>
</template>
