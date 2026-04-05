<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click="handleCancel"
    >
      <div
        class="relative w-full max-w-md mx-4 rounded-lg shadow-xl"
        :class="[
          isDark
            ? 'bg-gray-900 border border-gray-700'
            : 'bg-white border border-gray-200'
        ]"
        @click.stop
      >
        <div class="p-6">
          <!-- Icon and Title -->
          <div class="flex items-start gap-4">
            <div
              class="flex-shrink-0 flex h-12 w-12 items-center justify-center rounded-full"
              :class="[
                variant === 'danger'
                  ? isDark
                    ? 'bg-red-900/30'
                    : 'bg-red-50'
                  : isDark
                    ? 'bg-amber-900/30'
                    : 'bg-amber-50'
              ]"
            >
              <svg
                class="h-6 w-6"
                :class="[
                  variant === 'danger' ? 'text-red-600' : 'text-amber-600'
                ]"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  fill-rule="evenodd"
                  d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                  clip-rule="evenodd"
                />
              </svg>
            </div>

            <div class="flex-1">
              <h3
                class="text-lg font-medium"
                :class="isDark ? 'text-white' : 'text-gray-900'"
              >
                {{ title }}
              </h3>
            </div>
          </div>

          <!-- Message -->
          <div
            class="mt-4 text-sm"
            :class="isDark ? 'text-gray-300' : 'text-gray-600'"
          >
            {{ message }}
          </div>

          <!-- Action buttons -->
          <div class="mt-6 flex gap-3 justify-end">
            <button
              type="button"
              class="inline-flex items-center justify-center px-4 py-2 border rounded-md shadow-sm text-sm font-medium transition-colors duration-150"
              :class="isDark
                ? 'bg-gray-800 text-gray-200 border-gray-600 hover:bg-gray-700'
                : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'"
              @click="handleCancel"
            >
              {{ cancelText }}
            </button>

            <button
              type="button"
              class="inline-flex items-center justify-center px-4 py-2 border border-transparent rounded-md shadow-sm text-sm font-medium text-white transition-colors duration-150 cursor-pointer"
              :class="[
                variant === 'danger'
                  ? 'bg-red-600 hover:bg-red-700'
                  : 'bg-emerald-600 hover:bg-emerald-700'
              ]"
              @click="handleConfirm"
            >
              {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useTheme } from '../composables/useTheme'

interface Props {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  variant?: 'danger' | 'warning'
}

withDefaults(defineProps<Props>(), {
  confirmText: "Yes, I'm sure",
  cancelText: 'No, cancel',
  variant: 'danger'
})

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

const { isDark } = useTheme()

function handleConfirm() {
  emit('confirm')
}

function handleCancel() {
  emit('cancel')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    handleCancel()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>
