<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { BaseConfirmModal, BaseToast, BaseToastEnum, useTheme, useToast } from 'mgv-backoffice'
import {
  PencilSquareIcon,
  ClipboardDocumentListIcon,
  CubeIcon,
  ArrowPathIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
import type { MockResponse } from '../types/mock'
import { mockApi } from '../services/api'
import { deleteMock as deleteMockApi } from '../services/mockService'
import { checkStubExists } from '../services/stubService'

interface Props {
  mock: MockResponse
  currentProjectId: string
  // Hide the Edit button when already on the edit page so the slot
  // doesn't navigate to itself.
  showEdit?: boolean
}
const props = withDefaults(defineProps<Props>(), { showEdit: true })

const emit = defineEmits<{
  deleted: []
}>()

const router = useRouter()
const { isDark } = useTheme()
const { showToast, toastMessage, toastType, showToastMessage } = useToast()

type StubStatus = 'loading' | 'exists' | 'missing' | 'creating' | 'error'
const stubStatus = ref<StubStatus>('loading')

async function refreshStubStatus() {
  stubStatus.value = 'loading'
  try {
    const exists = await checkStubExists(props.mock.id)
    stubStatus.value = exists ? 'exists' : 'missing'
  } catch (_e) {
    stubStatus.value = 'error'
  }
}

onMounted(() => {
  refreshStubStatus()
})

function getMockUrl(mock: MockResponse): string {
  return mock.request.url || mock.request.urlPath || mock.request.urlPattern || mock.request.urlPathPattern || '—'
}

function editMock() {
  router.push({ name: 'edit-mock', params: { id: props.currentProjectId, mockId: props.mock.id } })
}

function viewMockLogs() {
  const url = getMockUrl(props.mock)
  const search = url && url !== '—' ? url : props.mock.name
  router.push({ name: 'logs', query: { search } })
}

function viewMockStub() {
  router.push({ name: 'stub-detail', params: { id: props.mock.id } })
}

async function createStubForMock() {
  stubStatus.value = 'creating'
  try {
    await mockApi.republish(props.mock.id)
    stubStatus.value = 'exists'
    showToastMessage(`Stub created for '${props.mock.name}'`, BaseToastEnum.SUCCESS)
  } catch (e) {
    stubStatus.value = 'missing'
    showToastMessage(
      e instanceof Error ? `Failed to create stub: ${e.message}` : 'Failed to create stub',
      BaseToastEnum.ERROR,
    )
  }
}

const showDeleteModal = ref(false)
const isDeleting = ref(false)
function openDelete() { showDeleteModal.value = true }
async function confirmDelete() {
  if (isDeleting.value) return
  isDeleting.value = true
  try {
    await deleteMockApi(props.mock.id)
    showToastMessage('Mock deleted', BaseToastEnum.SUCCESS)
    emit('deleted')
  } catch (e) {
    showToastMessage(e instanceof Error ? e.message : 'Failed to delete mock', BaseToastEnum.ERROR)
  } finally {
    isDeleting.value = false
    showDeleteModal.value = false
  }
}
function cancelDelete() {
  if (isDeleting.value) return
  showDeleteModal.value = false
}
</script>

<template>
  <div>
    <div class="flex items-center flex-wrap gap-2 rounded-xl border p-3"
      :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200 shadow-sm'">
      <button
        v-if="showEdit"
        @click="editMock"
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-emerald-400 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'"
        title="Edit this mock's request matcher and response definition"
      >
        <PencilSquareIcon class="w-4 h-4" />
        Edit
      </button>
      <button
        @click="viewMockLogs"
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-sky-400 hover:bg-sky-500/10' : 'text-sky-600 hover:bg-sky-50'"
        title="View request logs for this mock"
      >
        <ClipboardDocumentListIcon class="w-4 h-4" />
        Logs
      </button>
      <button
        v-if="stubStatus === 'missing'"
        @click="createStubForMock"
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-amber-300 hover:bg-amber-500/10' : 'text-amber-700 hover:bg-amber-50'"
        title="No matching stub on WireMock — click to create it from this mock"
      >
        click to create
      </button>
      <button
        v-else-if="stubStatus === 'creating'"
        disabled
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-not-allowed opacity-70"
        :class="isDark ? 'text-amber-300' : 'text-amber-700'"
        title="Creating stub on WireMock…"
      >
        <ArrowPathIcon class="w-4 h-4 animate-spin" />
        Creating…
      </button>
      <button
        v-else
        @click="viewMockStub"
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-indigo-400 hover:bg-indigo-500/10' : 'text-indigo-600 hover:bg-indigo-50'"
        title="Open the raw WireMock stub"
      >
        <CubeIcon class="w-4 h-4" />
        Stub
      </button>
      <button
        @click="openDelete"
        class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
        title="Permanently delete this mock and its WireMock stub"
      >
        <TrashIcon class="w-4 h-4" />
        Delete
      </button>
    </div>

    <BaseConfirmModal
      v-if="showDeleteModal"
      title="Delete Mock"
      :message="`Are you sure you want to delete mock '${mock.name}'? The mock will be removed from both the database and the WireMock service. This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      submitting-text="Deleting..."
      variant="danger"
      :submitting="isDeleting"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />

    <BaseToast
      v-if="showToast"
      :mode="toastType"
      :description="toastMessage"
    />
  </div>
</template>
