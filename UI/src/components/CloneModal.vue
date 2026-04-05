<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      role="dialog"
      aria-modal="true"
      @click="handleBackdropClick"
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
              :class="isDark ? 'bg-emerald-900/30' : 'bg-emerald-50'"
            >
              <DocumentDuplicateIcon class="h-6 w-6 text-emerald-600" />
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

          <!-- Name input -->
          <div class="mt-4">
            <label
              class="block text-sm font-medium mb-1"
              :class="isDark ? 'text-gray-300' : 'text-gray-700'"
            >
              Name
            </label>
            <input
              ref="nameInput"
              v-model="cloneName"
              type="text"
              :placeholder="placeholder"
              class="w-full px-3 py-2 rounded-md border text-sm transition-colors"
              :class="isDark
                ? 'bg-gray-800 border-gray-600 text-gray-100 placeholder-gray-500 focus:outline-none focus:border-emerald-500'
                : 'bg-white border-gray-300 text-gray-900 placeholder-gray-400 focus:outline-none focus:border-emerald-500'"
              @keyup.enter="handleConfirm"
            />
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
              Cancel
            </button>
            <button
              type="button"
              :disabled="!cloneName.trim() || submitting"
              class="inline-flex items-center justify-center px-4 py-2 border border-transparent rounded-md shadow-sm text-sm font-medium text-white transition-colors duration-150"
              :class="[
                !cloneName.trim() || submitting
                  ? 'bg-emerald-400 cursor-not-allowed'
                  : 'bg-emerald-600 hover:bg-emerald-700 cursor-pointer'
              ]"
              @click="handleConfirm"
            >
              {{ submitting ? 'Cloning...' : confirmText }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { DocumentDuplicateIcon } from '@heroicons/vue/24/outline'
import { useTheme } from '../composables/useTheme'

interface Props {
  title: string
  message: string
  initialName?: string
  placeholder?: string
  confirmText?: string
}

const props = withDefaults(defineProps<Props>(), {
  initialName: '',
  placeholder: 'Enter name...',
  confirmText: 'Clone',
})

const emit = defineEmits<{
  confirm: [name: string]
  cancel: []
}>()

const { isDark } = useTheme()
const cloneName = ref(props.initialName)
const submitting = ref(false)
const nameInput = ref<HTMLInputElement | null>(null)

onMounted(async () => {
  await nextTick()
  nameInput.value?.focus()
})

function handleConfirm() {
  if (!cloneName.value.trim() || submitting.value) return
  emit('confirm', cloneName.value.trim())
}

function handleCancel() {
  emit('cancel')
}

// Backdrop click: only treat it as cancel if the user hasn't modified
// the name. Otherwise a stray click outside the dialog would silently
// throw away the typed value. We compare against the initial prop
// rather than "is empty" so both the suggested default and any typed
// edit are preserved.
function handleBackdropClick() {
  if (cloneName.value.trim() === (props.initialName ?? '').trim()) {
    handleCancel()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') handleCancel()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

defineExpose({ submitting })
</script>
