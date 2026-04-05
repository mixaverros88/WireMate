<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click="handleCancel"
    >
      <div
        class="relative w-full max-w-2xl mx-4 rounded-lg shadow-xl max-h-[90vh] flex flex-col"
        :class="isDark ? 'bg-gray-900 border border-gray-700' : 'bg-white border border-gray-200'"
        @click.stop
      >
        <!-- Header -->
        <div class="p-6 pb-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <div class="flex items-start gap-4">
            <div
              class="flex-shrink-0 flex h-12 w-12 items-center justify-center rounded-full"
              :class="isDark ? 'bg-emerald-500/10' : 'bg-emerald-50'"
            >
              <ArrowUpTrayIcon class="h-6 w-6" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
            </div>
            <div class="flex-1">
              <h3 class="text-lg font-medium" :class="isDark ? 'text-white' : 'text-gray-900'">
                Import from Postman
              </h3>
              <p class="text-sm mt-1" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                Upload a Postman collection (v2.x). Each request is imported as a mock in this project.
              </p>
            </div>
          </div>
        </div>

        <!-- Body -->
        <div class="p-6 overflow-y-auto flex-1">
          <!-- Step 1: file picker (before parsing) -->
          <div v-if="!parseResult && !parseError">
            <label
              class="flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-6 py-10 text-center cursor-pointer transition-colors"
              :class="isDark
                ? 'border-gray-700 hover:border-emerald-500 text-gray-400 hover:text-emerald-400'
                : 'border-gray-300 hover:border-emerald-500 text-gray-500 hover:text-emerald-600'"
            >
              <DocumentArrowUpIcon class="w-10 h-10" />
              <p class="text-sm font-medium">Click to select a Postman collection (.json)</p>
              <p class="text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                Exported from Postman → Export → Collection v2.1
              </p>
              <input
                type="file"
                accept="application/json,.json"
                class="hidden"
                @change="onFileSelected"
              />
            </label>
          </div>

          <!-- Step 2: parsing error -->
          <div v-else-if="parseError" class="rounded-lg p-4"
            :class="isDark ? 'bg-red-500/10 border border-red-500/30' : 'bg-red-50 border border-red-200'">
            <p class="text-sm font-medium" :class="isDark ? 'text-red-400' : 'text-red-700'">
              Could not parse collection
            </p>
            <p class="text-sm mt-1" :class="isDark ? 'text-red-300/80' : 'text-red-600'">
              {{ parseError }}
            </p>
            <button
              class="mt-3 text-sm font-medium underline cursor-pointer"
              :class="isDark ? 'text-red-400 hover:text-red-300' : 'text-red-700 hover:text-red-800'"
              @click="reset"
            >
              Choose a different file
            </button>
          </div>

          <!-- Step 3: review parsed requests -->
          <div v-else-if="parseResult">
            <!-- Collection summary -->
            <div class="mb-4 flex items-center justify-between">
              <div>
                <p class="text-sm font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">
                  {{ parseResult.collectionName }}
                </p>
                <p class="text-xs mt-0.5" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  {{ parseResult.requests.length }} request{{ parseResult.requests.length === 1 ? '' : 's' }} found
                </p>
              </div>
              <button
                v-if="!isImporting && !importComplete"
                class="text-xs font-medium cursor-pointer"
                :class="isDark ? 'text-gray-400 hover:text-gray-200' : 'text-gray-500 hover:text-gray-700'"
                @click="reset"
              >
                Change file
              </button>
            </div>

            <!-- Preview list -->
            <div class="rounded-lg border overflow-hidden"
              :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <div class="max-h-72 overflow-y-auto divide-y"
                :class="isDark ? 'divide-gray-800' : 'divide-gray-100'">
                <div
                  v-for="(item, idx) in itemsWithStatus"
                  :key="idx"
                  class="flex items-center gap-3 px-4 py-2.5 text-sm"
                  :class="isDark ? 'bg-gray-900' : 'bg-white'"
                >
                  <span
                    class="inline-flex items-center px-2 py-0.5 rounded text-xs font-bold uppercase tracking-wide min-w-[3.5rem] justify-center"
                    :class="methodColor(item.request.method)"
                  >
                    {{ item.request.method }}
                  </span>
                  <div class="flex-1 min-w-0">
                    <p class="truncate font-medium" :class="isDark ? 'text-gray-200' : 'text-gray-800'">
                      {{ item.request.name }}
                    </p>
                    <p class="truncate text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                      {{ item.request.path }}
                    </p>
                  </div>
                  <span v-if="item.status === 'pending'" class="text-xs"
                    :class="isDark ? 'text-gray-600' : 'text-gray-400'">
                    Pending
                  </span>
                  <span v-else-if="item.status === 'in-progress'" class="text-xs"
                    :class="isDark ? 'text-emerald-400' : 'text-emerald-600'">
                    Importing…
                  </span>
                  <span v-else-if="item.status === 'success'" class="text-xs flex items-center gap-1"
                    :class="isDark ? 'text-emerald-400' : 'text-emerald-600'">
                    <CheckCircleIcon class="w-4 h-4" /> Created
                  </span>
                  <span v-else-if="item.status === 'error'" class="text-xs flex items-center gap-1"
                    :title="item.error"
                    :class="isDark ? 'text-red-400' : 'text-red-600'">
                    <XCircleIcon class="w-4 h-4" /> Failed
                  </span>
                </div>
              </div>
            </div>

            <!-- Post-import summary -->
            <div v-if="importComplete" class="mt-4 rounded-lg p-3 text-sm"
              :class="importErrorCount > 0
                ? (isDark ? 'bg-amber-500/10 border border-amber-500/30 text-amber-300' : 'bg-amber-50 border border-amber-200 text-amber-700')
                : (isDark ? 'bg-emerald-500/10 border border-emerald-500/30 text-emerald-300' : 'bg-emerald-50 border border-emerald-200 text-emerald-700')">
              Imported {{ importSuccessCount }} of {{ parseResult.requests.length }} mocks.
              <span v-if="importErrorCount > 0"> {{ importErrorCount }} failed.</span>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="p-4 border-t flex justify-end gap-3"
          :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <button
            type="button"
            :disabled="isImporting"
            class="inline-flex items-center justify-center px-4 py-2 border rounded-md shadow-sm text-sm font-medium transition-colors duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDark
              ? 'bg-gray-800 text-gray-200 border-gray-600 hover:bg-gray-700'
              : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'"
            @click="handleCancel"
          >
            {{ importComplete ? 'Close' : 'Cancel' }}
          </button>
          <button
            v-if="parseResult && !importComplete"
            type="button"
            :disabled="isImporting"
            class="inline-flex items-center justify-center px-4 py-2 rounded-md shadow-sm text-sm font-medium text-white transition-colors duration-150 cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
            :class="isDark ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-emerald-600 hover:bg-emerald-700'"
            @click="runImport"
          >
            {{ isImporting ? 'Importing…' : `Import ${parseResult.requests.length} mock${parseResult.requests.length === 1 ? '' : 's'}` }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  ArrowUpTrayIcon,
  DocumentArrowUpIcon,
  CheckCircleIcon,
  XCircleIcon,
} from '@heroicons/vue/24/outline'
import { useTheme } from '../composables/useTheme'
import { mockApi } from '../services/api'
import {
  parsePostmanCollection,
  PostmanParseError,
  type ParseResult,
  type ParsedRequest,
} from '../utils/postmanImport'

interface Props {
  projectId: string
}
const props = defineProps<Props>()

const emit = defineEmits<{
  imported: [count: number]
  cancel: []
}>()

const { isDark } = useTheme()

type ItemStatus = 'pending' | 'in-progress' | 'success' | 'error'

interface ItemWithStatus {
  request: ParsedRequest
  status: ItemStatus
  error?: string
}

const parseResult = ref<ParseResult | null>(null)
const parseError = ref<string>('')
const items = ref<ItemWithStatus[]>([])
const isImporting = ref(false)
const importComplete = ref(false)

const itemsWithStatus = computed(() => items.value)

const importSuccessCount = computed(
  () => items.value.filter(i => i.status === 'success').length,
)
const importErrorCount = computed(
  () => items.value.filter(i => i.status === 'error').length,
)

function methodColor(method: string): string {
  const m = method.toUpperCase()
  if (m === 'GET') return isDark.value ? 'bg-emerald-500/15 text-emerald-400' : 'bg-green-100 text-green-700'
  if (m === 'POST') return isDark.value ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'
  if (m === 'PUT') return isDark.value ? 'bg-amber-500/15 text-amber-400' : 'bg-yellow-100 text-yellow-700'
  if (m === 'DELETE') return isDark.value ? 'bg-red-500/15 text-red-400' : 'bg-red-100 text-red-700'
  if (m === 'PATCH') return isDark.value ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'
  return isDark.value ? 'bg-gray-500/15 text-gray-400' : 'bg-gray-100 text-gray-600'
}

async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    let json: unknown
    try {
      json = JSON.parse(text)
    } catch {
      throw new PostmanParseError('File is not valid JSON')
    }
    const result = parsePostmanCollection(json, props.projectId)
    parseResult.value = result
    items.value = result.requests.map(r => ({ request: r, status: 'pending' as ItemStatus }))
    parseError.value = ''
  } catch (err) {
    parseError.value = err instanceof Error ? err.message : 'Failed to parse collection'
    parseResult.value = null
    items.value = []
  } finally {
    // Reset the input so selecting the same file again re-triggers change.
    input.value = ''
  }
}

async function runImport() {
  if (!parseResult.value || isImporting.value) return
  isImporting.value = true
  try {
    for (const item of items.value) {
      item.status = 'in-progress'
      try {
        await mockApi.create(item.request.payload)
        item.status = 'success'
      } catch (err) {
        item.status = 'error'
        item.error = err instanceof Error ? err.message : 'Unknown error'
      }
    }
    importComplete.value = true
    if (importSuccessCount.value > 0) {
      emit('imported', importSuccessCount.value)
    }
  } finally {
    isImporting.value = false
  }
}

function reset() {
  if (isImporting.value) return
  parseResult.value = null
  parseError.value = ''
  items.value = []
  importComplete.value = false
}

function handleCancel() {
  if (isImporting.value) return
  emit('cancel')
}
</script>
