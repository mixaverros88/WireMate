<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { BaseButton, BaseSpinner, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import { FolderIcon, EyeIcon, TrashIcon, MagnifyingGlassIcon, DocumentDuplicateIcon } from '@heroicons/vue/24/outline'
import type { Project } from '../types/project'
import { fetchProjects, deleteProject, cloneProject } from '../services/projectService'
import { useTheme } from '../composables/useTheme'
import ConfirmModal from '../components/ConfirmModal.vue'
import CloneModal from '../components/CloneModal.vue'

const router = useRouter()
const { isDark } = useTheme()
const projects = ref<Project[]>([])
const loading = ref(true)
const searchQuery = ref('')
const toast = ref<{ show: boolean; message: string; mode: BaseToastEnum }>({
  show: false,
  message: '',
  mode: BaseToastEnum.SUCCESS,
})

const showDeleteModal = ref(false)
const projectToDelete = ref<Project | null>(null)
const showCloneModal = ref(false)
const projectToClone = ref<Project | null>(null)
const cloneModalRef = ref<InstanceType<typeof CloneModal> | null>(null)

const filteredProjects = computed(() => {
  if (!searchQuery.value.trim()) {
    return projects.value
  }
  const query = searchQuery.value.toLowerCase()
  return projects.value.filter(project =>
    project.name.toLowerCase().includes(query)
  )
})

onMounted(async () => {
  await loadProjects()
})

async function loadProjects() {
  loading.value = true
  try {
    projects.value = await fetchProjects()
  } catch (_e) {
    showToast('Failed to load projects', BaseToastEnum.ERROR)
  } finally {
    loading.value = false
  }
}

function goToCreateProject() {
  router.push({ name: 'create-project' })
}

function viewProject(id: string) {
  router.push({ name: 'project', params: { id } })
}

function handleDelete(project: Project) {
  projectToDelete.value = project
  showDeleteModal.value = true
}

async function confirmDelete() {
  if (!projectToDelete.value) return
  try {
    await deleteProject(projectToDelete.value.id)
    projects.value = projects.value.filter(p => p.id !== projectToDelete.value!.id)
    showToast('Project deleted', BaseToastEnum.SUCCESS)
  } catch (_e) {
    showToast('Failed to delete project', BaseToastEnum.ERROR)
  } finally {
    showDeleteModal.value = false
    projectToDelete.value = null
  }
}

function cancelDelete() {
  showDeleteModal.value = false
  projectToDelete.value = null
}

function handleClone(project: Project) {
  projectToClone.value = project
  showCloneModal.value = true
}

async function confirmClone(name: string) {
  if (!projectToClone.value || !cloneModalRef.value) return
  cloneModalRef.value.submitting = true
  try {
    const cloned = await cloneProject(projectToClone.value.id, { name })
    projects.value.push(cloned)
    showToast('Project cloned successfully', BaseToastEnum.SUCCESS)
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to clone project'
    showToast(message, BaseToastEnum.ERROR)
  } finally {
    if (cloneModalRef.value) cloneModalRef.value.submitting = false
    showCloneModal.value = false
    projectToClone.value = null
  }
}

function cancelClone() {
  showCloneModal.value = false
  projectToClone.value = null
}

function showToast(message: string, mode: BaseToastEnum) {
  toast.value = { show: true, message, mode }
  setTimeout(() => {
    toast.value.show = false
  }, 3000)
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <div>
    <div class="max-w-5xl mx-auto px-4 sm:px-6 py-6 sm:py-8">
      <!-- Loading -->
      <div v-if="loading" class="flex justify-center py-20">
        <BaseSpinner />
      </div>

      <!-- Empty state -->
      <div v-else-if="projects.length === 0" class="text-center py-20">
        <FolderIcon class="w-16 h-16 mx-auto mb-4" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
        <h2 class="text-lg font-semibold mb-2" :class="isDark ? 'text-gray-200' : 'text-gray-700'">No projects yet</h2>
        <p class="mb-6" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Create your first project to start building WireMock stubs.</p>
        <BaseButton
          description="Create Project"
          :color="BaseButtonEnum.GREEN"
          @click="goToCreateProject"
        />
      </div>

      <!-- Search bar and project grid -->
      <div v-else>
        <!-- Search bar -->
        <div class="mb-6 relative">
          <MagnifyingGlassIcon class="absolute left-3 top-1/2 transform -translate-y-1/2 w-5 h-5"
            :class="isDark ? 'text-gray-500' : 'text-gray-400'" />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="Search projects..."
            class="w-full pl-10 pr-4 py-2 rounded-lg border transition-colors"
            :class="isDark
              ? 'bg-gray-800 border-gray-700 text-gray-100 placeholder-gray-500 focus:outline-none focus:border-emerald-500'
              : 'bg-white border-gray-200 text-gray-900 placeholder-gray-400 focus:outline-none focus:border-emerald-500'"
          />
        </div>

        <!-- Project list — 1 per row -->
        <div v-if="filteredProjects.length > 0" class="grid grid-cols-1 gap-5">
          <div
            v-for="project in filteredProjects"
            :key="project.id"
          class="rounded-xl border p-5 flex flex-col justify-between transition-all"
          :class="isDark
            ? 'bg-gray-900 border-gray-700 hover:bg-gray-950'
            : 'bg-white border-gray-200 shadow-sm hover:bg-gray-100'"
        >
          <!-- Top: icon + name -->
          <div>
            <div class="flex items-center gap-3 mb-1">
              <div class="w-10 h-10 rounded-lg flex items-center justify-center shrink-0"
                :class="isDark ? 'bg-emerald-500/10' : 'bg-emerald-50'">
                <FolderIcon class="w-5 h-5" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
              </div>
              <h3 class="font-semibold truncate" :class="isDark ? 'text-gray-100' : 'text-gray-800'">{{ project.name }}</h3>
            </div>
            <!--
              Use gray-500 in light mode (4.6:1 on white) instead of gray-400 (2.6:1)
              to meet WCAG AA for small text.
            -->
            <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-500'">Created {{ formatDate(project.createdAt) }}</p>
          </div>

          <!-- Bottom: actions -->
          <div class="flex items-center gap-2 mt-4 pt-4 border-t"
            :class="isDark ? 'border-gray-800' : 'border-gray-100'">
            <!--
              Action buttons:
              * py-2.5 gets total height ~40px, closer to the 44px touch-target minimum
                (and much closer than the previous py-1.5 → 28px).
              * focus-visible rings give keyboard users a clear indicator per action
                (outline:none on focus was previously the only rule).
              * aria-label carries the project name so screen readers announce
                "View Tests123" / "Delete Tests123" instead of bare "View" x3.
            -->
            <button
              type="button"
              :aria-label="`View ${project.name}`"
              @click="viewProject(project.id)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-lg text-xs font-medium transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-emerald-500"
              :class="isDark
                ? 'text-emerald-400 hover:bg-emerald-500/10 focus-visible:ring-offset-gray-900'
                : 'text-emerald-600 hover:bg-emerald-50 focus-visible:ring-offset-white'"
            >
              <EyeIcon class="w-4 h-4" />
              View
            </button>
            <button
              type="button"
              :aria-label="`Clone ${project.name}`"
              @click="handleClone(project)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-lg text-xs font-medium transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-amber-500"
              :class="isDark
                ? 'text-amber-400 hover:bg-amber-500/10 focus-visible:ring-offset-gray-900'
                : 'text-amber-600 hover:bg-amber-50 focus-visible:ring-offset-white'"
            >
              <DocumentDuplicateIcon class="w-4 h-4" />
              Clone
            </button>
            <button
              type="button"
              :aria-label="`Delete ${project.name}`"
              @click="handleDelete(project)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-lg text-xs font-medium transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-red-500"
              :class="isDark
                ? 'text-red-400 hover:bg-red-500/10 focus-visible:ring-offset-gray-900'
                : 'text-red-500 hover:bg-red-50 focus-visible:ring-offset-white'"
            >
              <TrashIcon class="w-4 h-4" />
              Delete
            </button>
          </div>
        </div>
        </div>

        <!-- No search results state -->
        <div v-else class="text-center py-20">
          <FolderIcon class="w-16 h-16 mx-auto mb-4" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
          <h2 class="text-lg font-semibold mb-2" :class="isDark ? 'text-gray-200' : 'text-gray-700'">No projects found</h2>
          <p :class="isDark ? 'text-gray-400' : 'text-gray-500'">Try adjusting your search query.</p>
        </div>
      </div>
    </div>

    <!-- Delete confirmation modal -->
    <ConfirmModal
      v-if="showDeleteModal"
      title="Delete Project"
      :message="`Are you sure you want to delete project '${projectToDelete?.name}'? All mocks under this project will also be deleted, both from the database and the WireMock service. This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />

    <!-- Clone modal -->
    <CloneModal
      v-if="showCloneModal"
      ref="cloneModalRef"
      title="Clone Project"
      :message="`Create a copy of '${projectToClone?.name}' with all its mocks.`"
      :initial-name="`${projectToClone?.name} (copy)`"
      placeholder="Enter project name..."
      confirm-text="Clone"
      @confirm="confirmClone"
      @cancel="cancelClone"
    />

    <!-- Toast -->
    <BaseToast
      v-if="toast.show"
      :mode="toast.mode"
      :description="toast.message"
    />
  </div>
</template>
