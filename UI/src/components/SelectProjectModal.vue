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
              :class="isDark ? 'bg-emerald-900/30' : 'bg-emerald-50'"
            >
              <FolderIcon class="h-6 w-6" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
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
            v-if="message"
            class="mt-4 text-sm"
            :class="isDark ? 'text-gray-300' : 'text-gray-600'"
          >
            {{ message }}
          </div>

          <!-- Loading projects -->
          <div v-if="loadingProjects" class="mt-4 flex justify-center py-6">
            <BaseSpinner />
          </div>

          <!-- Error loading projects -->
          <div v-else-if="loadError" class="mt-4 text-sm text-red-500">
            {{ loadError }}
          </div>

          <!--
            Search input. Only shows when we actually have projects to filter;
            the empty-state branch below is simpler without a redundant
            "Search projects..." affordance sitting above it.
          -->
          <div v-if="!loadingProjects && !loadError && projects.length > 0" class="mt-4">
            <div class="relative">
              <MagnifyingGlassIcon
                class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4"
                :class="isDark ? 'text-gray-500' : 'text-gray-400'"
              />
              <input
                ref="searchInputRef"
                v-model="searchQuery"
                type="text"
                placeholder="Search projects..."
                class="w-full pl-9 pr-3 py-2 text-sm rounded-md border outline-none transition-colors"
                :class="[
                  isDark
                    ? 'bg-gray-800 border-gray-600 text-gray-200 placeholder-gray-500 focus:border-emerald-500'
                    : 'bg-white border-gray-300 text-gray-700 placeholder-gray-400 focus:border-emerald-500'
                ]"
              />
            </div>
          </div>

          <!-- Project list -->
          <div v-if="!loadingProjects && !loadError" class="mt-3 max-h-64 overflow-y-auto">
            <div
              v-for="proj in filteredProjects"
              :key="proj.id"
              class="flex items-center gap-3 px-3 py-2.5 rounded-lg cursor-pointer transition-colors"
              :class="[
                selectedProjectId === proj.id
                  ? isDark
                    ? 'bg-emerald-500/15 border border-emerald-500/30'
                    : 'bg-emerald-50 border border-emerald-200'
                  : isDark
                    ? 'hover:bg-gray-800 border border-transparent'
                    : 'hover:bg-gray-50 border border-transparent'
              ]"
              @click="selectedProjectId = proj.id"
            >
              <FolderIcon class="w-5 h-5 flex-shrink-0" :class="isDark ? 'text-gray-500' : 'text-gray-400'" />
              <div class="flex-1 min-w-0">
                <p class="text-sm font-medium truncate" :class="isDark ? 'text-gray-200' : 'text-gray-700'">
                  {{ proj.name }}
                </p>
              </div>
              <div v-if="selectedProjectId === proj.id"
                class="w-5 h-5 rounded-full flex items-center justify-center bg-emerald-600">
                <svg class="w-3 h-3 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              </div>
            </div>

            <!--
              Empty states — `projects.length === 0` means the backend has
              no projects at all (the user needs to create one before they
              can pick), `filteredProjects.length === 0` means the search
              query doesn't match anything. We differentiate so the user
              gets actionable copy instead of a generic "no results".
            -->
            <div v-if="projects.length === 0" class="text-center py-6">
              <p class="text-sm" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                No projects yet. Create one before adopting this stub.
              </p>
            </div>
            <div v-else-if="filteredProjects.length === 0" class="text-center py-6">
              <p class="text-sm" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                No projects match your search.
              </p>
            </div>
          </div>

          <!-- Action buttons -->
          <div class="mt-6 flex gap-3 justify-end">
            <button
              type="button"
              :disabled="submitting"
              class="inline-flex items-center justify-center px-4 py-2 border rounded-md shadow-sm text-sm font-medium transition-colors duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
              :class="isDark
                ? 'bg-gray-800 text-gray-200 border-gray-600 hover:bg-gray-700 cursor-pointer'
                : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50 cursor-pointer'"
              @click="handleCancel"
            >
              Cancel
            </button>
            <button
              type="button"
              :disabled="!selectedProjectId || submitting"
              class="inline-flex items-center justify-center px-4 py-2 border border-transparent rounded-md shadow-sm text-sm font-medium text-white transition-colors duration-150"
              :class="[
                !selectedProjectId || submitting
                  ? 'bg-emerald-400 cursor-not-allowed'
                  : 'bg-emerald-600 hover:bg-emerald-700 cursor-pointer'
              ]"
              @click="handleConfirm"
            >
              {{ submitting ? (submittingText || 'Working...') : (confirmText || 'Create') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * SelectProjectModal — generic "pick a project from the backend list and
 * confirm" dialog. Loads GET /api/projects on mount, renders them in a
 * searchable list, and emits `confirm(projectId)` when the user picks one
 * and clicks the action button.
 *
 * Parents control the loading state via `defineExpose({ submitting })`:
 * flip it to `true` when the post-select work kicks off so the action
 * button switches to its in-flight label, and flip it back to `false`
 * if the work fails (the parent will typically close the modal on
 * success).
 *
 * Used by StubDetailView to adopt a raw WireMock stub into a WireMate
 * project — the stub's UUID doesn't exist as a mock, the user picks a
 * project, and the parent POSTs /api/mocks with the stub's definition.
 */
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { BaseSpinner } from 'mgv-backoffice'
import { FolderIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import type { Project } from '../types/project'
import { fetchProjects } from '../services/projectService'
import { useTheme } from '../composables/useTheme'

interface Props {
  title: string
  message?: string
  confirmText?: string
  submittingText?: string
}

defineProps<Props>()

const emit = defineEmits<{
  confirm: [projectId: string]
  cancel: []
}>()

const { isDark } = useTheme()
const projects = ref<Project[]>([])
const loadingProjects = ref(true)
const loadError = ref('')
const selectedProjectId = ref<string | null>(null)
const submitting = ref(false)
const searchQuery = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)

const filteredProjects = computed(() =>
  projects.value.filter(p =>
    p.name.toLowerCase().includes(searchQuery.value.toLowerCase())
  )
)

onMounted(async () => {
  loadingProjects.value = true
  try {
    projects.value = await fetchProjects()
  } catch (_e) {
    loadError.value = 'Failed to load projects'
  } finally {
    loadingProjects.value = false
    await nextTick()
    searchInputRef.value?.focus()
  }
})

function handleConfirm() {
  if (!selectedProjectId.value || submitting.value) return
  emit('confirm', selectedProjectId.value)
}

function handleCancel() {
  if (submitting.value) return
  emit('cancel')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') handleCancel()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

defineExpose({ submitting })
</script>
