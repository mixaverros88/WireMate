<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BaseButton, BaseSpinner, BaseButtonEnum } from 'mgv-backoffice'
import { ArrowLeftIcon } from '@heroicons/vue/24/outline'
import type { MockResponse } from '../types/mock'
import { mockApi } from '../services/api'
import CreateMock from '../components/CreateMock.vue'
import { useTheme } from '../composables/useTheme'

const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()

const mock = ref<MockResponse | null>(null)
const loading = ref(true)
const error = ref('')

const projectId = route.params.id as string
const mockId = route.params.mockId as string

onMounted(async () => {
  loading.value = true
  try {
    mock.value = await mockApi.fetchById(mockId)
  } catch (_e) {
    error.value = 'Failed to load mock'
  } finally {
    loading.value = false
  }
})

function goBack() {
  router.push({ name: 'project', params: { id: projectId } })
}

function onMockUpdated(_mockId: string) {
  router.push({ name: 'project', params: { id: projectId } })
}
</script>

<template>
  <div>
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-6 sm:py-8">
      <!-- Back button -->
      <button
        class="inline-flex items-center gap-2 mb-6 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
        @click="goBack"
      >
        <ArrowLeftIcon class="w-4 h-4" />
        Back to Project
      </button>

      <!-- Loading -->
      <div v-if="loading" class="flex justify-center py-20">
        <BaseSpinner />
      </div>

      <!-- Error -->
      <div v-else-if="error" class="text-center py-20">
        <p class="text-red-500 mb-4">{{ error }}</p>
        <BaseButton
          description="Back to Project"
          :color="BaseButtonEnum.GREEN"
          @click="goBack"
        />
      </div>

      <!-- Edit mock form -->
      <div v-else-if="mock">
        <CreateMock :mock="mock" @updated="onMockUpdated" />
      </div>
    </div>
  </div>
</template>
