<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { BaseButton, BaseSpinner, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import {
  ArrowPathIcon,
  HeartIcon,
  CogIcon,
  ExclamationTriangleIcon,
  ClockIcon,
  PlayIcon,
  StopIcon,
  SignalIcon,
  CameraIcon,
  PencilSquareIcon,
  CheckIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type { Scenario, GlobalSettings, HealthResponse, RecordingStatusResponse } from '../types/settings'
import {
  fetchScenarios,
  resetScenarios,
  setScenarioState,
  updateSettings,
  fetchSettings,
  resetAll,
  shutdownServer,
  fetchHealth,
  fetchVersion,
  startRecording,
  stopRecording,
  getRecordingStatus,
  snapshotRecording,
} from '../services/settingsService'
import { useTheme } from '../composables/useTheme'

const { isDark } = useTheme()

// ── State ───────────────────────────────────────────────────
const scenarios = ref<Scenario[]>([])
const health = ref<HealthResponse | null>(null)
const version = ref('')
const loadingScenarios = ref(true)
const loadingHealth = ref(true)

// Settings form
const delayEnabled = ref(false)
const delayType = ref<'fixed' | 'uniform' | 'lognormal'>('fixed')
const fixedDelay = ref(0)
const uniformLower = ref(50)
const uniformUpper = ref(200)
const lognormalMedian = ref(100)
const lognormalSigma = ref(0.1)
const settingsSaving = ref(false)

// Recording state
const recordingStatus = ref<RecordingStatusResponse['status']>('NeverStarted')
const recordingTargetUrl = ref('')
const recordingLoading = ref(false)
const snapshotLoading = ref(false)

// Scenario editing
const editingScenario = ref<string | null>(null)
const editingState = ref('')

// Confirmations
const confirmReset = ref(false)
const confirmShutdown = ref(false)
const shutdownLoading = ref(false)

// Toast
const toast = ref<{ show: boolean; message: string; mode: BaseToastEnum }>({
  show: false, message: '', mode: BaseToastEnum.SUCCESS,
})

const sectionClasses = computed(() =>
  isDark.value ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'
)

const inputClasses = computed(() =>
  isDark.value
    ? 'bg-gray-800 border-gray-600 text-gray-100 placeholder-gray-500 focus:ring-emerald-500 focus:border-emerald-500'
    : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500 focus:border-emerald-500'
)

const labelClasses = computed(() =>
  isDark.value ? 'text-gray-300' : 'text-gray-600'
)

// ── Lifecycle ───────────────────────────────────────────────
onMounted(async () => {
  await refreshAll()
})

async function refreshAll() {
  await Promise.all([loadScenarios(), loadHealth(), loadSettings(), loadRecordingStatus()])
}

// ── Methods ─────────────────────────────────────────────────
async function loadScenarios() {
  loadingScenarios.value = true
  try {
    const res = await fetchScenarios()
    scenarios.value = res.scenarios
  } catch {
    showToast('Failed to load scenarios', BaseToastEnum.ERROR)
  } finally {
    loadingScenarios.value = false
  }
}

async function loadHealth() {
  loadingHealth.value = true
  try {
    const [h, v] = await Promise.all([fetchHealth(), fetchVersion()])
    health.value = h
    version.value = v.version
  } catch {
    showToast('Failed to fetch server status', BaseToastEnum.ERROR)
  } finally {
    loadingHealth.value = false
  }
}

async function loadSettings() {
  try {
    const settings = await fetchSettings()
    if (settings.fixedDelay && settings.fixedDelay > 0) {
      delayEnabled.value = true
      delayType.value = 'fixed'
      fixedDelay.value = settings.fixedDelay
    } else if (settings.delayDistribution) {
      delayEnabled.value = true
      const dist = settings.delayDistribution
      if (dist.type === 'fixed') {
        delayType.value = 'fixed'
        fixedDelay.value = dist.milliseconds ?? 0
      } else if (dist.type === 'uniform') {
        delayType.value = 'uniform'
        uniformLower.value = dist.lower ?? 50
        uniformUpper.value = dist.upper ?? 200
      } else if (dist.type === 'lognormal') {
        delayType.value = 'lognormal'
        lognormalMedian.value = dist.median ?? 100
        lognormalSigma.value = dist.sigma ?? 0.1
      }
    }
  } catch {
    // Settings might not be available, ignore
  }
}

async function loadRecordingStatus() {
  try {
    const res = await getRecordingStatus()
    recordingStatus.value = res.status
  } catch {
    // Recording status not available
  }
}

async function handleStartRecording() {
  if (!recordingTargetUrl.value.trim()) {
    showToast('Please enter a target base URL', BaseToastEnum.ERROR)
    return
  }
  recordingLoading.value = true
  try {
    await startRecording(recordingTargetUrl.value.trim())
    recordingStatus.value = 'Recording'
    showToast('Recording started', BaseToastEnum.SUCCESS)
  } catch {
    showToast('Failed to start recording', BaseToastEnum.ERROR)
  } finally {
    recordingLoading.value = false
  }
}

async function handleStopRecording() {
  recordingLoading.value = true
  try {
    await stopRecording()
    recordingStatus.value = 'Stopped'
    showToast('Recording stopped — stubs generated', BaseToastEnum.SUCCESS)
  } catch {
    showToast('Failed to stop recording', BaseToastEnum.ERROR)
  } finally {
    recordingLoading.value = false
  }
}

async function handleSnapshot() {
  snapshotLoading.value = true
  try {
    await snapshotRecording()
    showToast('Snapshot taken', BaseToastEnum.SUCCESS)
  } catch {
    showToast('Failed to take snapshot', BaseToastEnum.ERROR)
  } finally {
    snapshotLoading.value = false
  }
}

function startEditScenario(scenario: Scenario) {
  editingScenario.value = scenario.name
  editingState.value = scenario.state
}

function cancelEditScenario() {
  editingScenario.value = null
  editingState.value = ''
}

async function saveScenarioState(scenario: Scenario) {
  const nextState = editingState.value
  try {
    await setScenarioState(scenario.name, nextState)
    // Replace the scenario immutably by id — avoids direct prop mutation of a
    // reactive object passed into the template, and keeps Vue's reactivity
    // tracking happy if the array is ever wrapped with a different reactive
    // primitive later on.
    const idx = scenarios.value.findIndex((s) => s.id === scenario.id)
    if (idx !== -1) {
      scenarios.value = scenarios.value.map((s, i) => (i === idx ? { ...s, state: nextState } : s))
    }
    showToast(`Scenario "${scenario.name}" set to "${nextState}"`, BaseToastEnum.SUCCESS)
  } catch {
    showToast('Failed to update scenario state', BaseToastEnum.ERROR)
  } finally {
    editingScenario.value = null
    editingState.value = ''
  }
}

async function handleResetScenarios() {
  try {
    await resetScenarios()
    showToast('All scenarios reset to Started', BaseToastEnum.SUCCESS)
    await loadScenarios()
  } catch {
    showToast('Failed to reset scenarios', BaseToastEnum.ERROR)
  }
}

async function handleSaveSettings() {
  settingsSaving.value = true
  try {
    const settings: GlobalSettings = {}
    if (delayEnabled.value) {
      if (delayType.value === 'fixed') {
        settings.fixedDelay = fixedDelay.value
      } else if (delayType.value === 'uniform') {
        settings.delayDistribution = { type: 'uniform', lower: uniformLower.value, upper: uniformUpper.value }
      } else {
        settings.delayDistribution = { type: 'lognormal', median: lognormalMedian.value, sigma: lognormalSigma.value }
      }
    }
    await updateSettings(settings)
    showToast('Global settings updated', BaseToastEnum.SUCCESS)
  } catch {
    showToast('Failed to update settings', BaseToastEnum.ERROR)
  } finally {
    settingsSaving.value = false
  }
}

async function handleResetAll() {
  try {
    await resetAll()
    showToast('Mappings and journal have been reset', BaseToastEnum.SUCCESS)
    confirmReset.value = false
    await Promise.all([loadScenarios(), loadHealth()])
  } catch {
    showToast('Failed to reset', BaseToastEnum.ERROR)
  }
}

async function handleShutdown() {
  shutdownLoading.value = true
  try {
    await shutdownServer()
    showToast('Server shutdown initiated', BaseToastEnum.SUCCESS)
    confirmShutdown.value = false
  } catch {
    showToast('Failed to shutdown server', BaseToastEnum.ERROR)
  } finally {
    shutdownLoading.value = false
  }
}

function formatUptime(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

function showToast(message: string, mode: BaseToastEnum) {
  toast.value = { show: true, message, mode }
  setTimeout(() => { toast.value.show = false }, 3000)
}
</script>

<template>
  <div>
    <div class="max-w-5xl mx-auto px-4 sm:px-6 py-4 sm:py-6 space-y-6">
      <!-- Toolbar -->
      <div class="flex items-center justify-end">
        <button @click="refreshAll"
          class="p-2 rounded-lg transition-colors cursor-pointer"
          :class="isDark ? 'hover:bg-gray-800 text-gray-400' : 'hover:bg-gray-100 text-gray-500'"
          title="Refresh">
          <ArrowPathIcon class="w-5 h-5" />
        </button>
      </div>

      <!-- ─── Server Health ───────────────────────────────── -->
      <div class="rounded-xl border p-5 transition-colors" :class="sectionClasses">
        <div class="flex items-center gap-2 mb-4">
          <HeartIcon class="w-4 h-4" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
          <h2 class="text-sm font-semibold" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Server Health</h2>
        </div>

        <!-- Description -->
        <p class="text-xs mb-4" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
          Monitors the WireMock server status, version, and uptime.
        </p>

        <div v-if="loadingHealth" class="flex justify-center py-6">
          <BaseSpinner />
        </div>

        <div v-else-if="health" class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Status</p>
            <div class="flex items-center gap-2">
              <span class="w-2.5 h-2.5 rounded-full" :class="health.status === 'healthy' ? 'bg-green-500' : 'bg-red-500'" />
              <span class="text-sm font-medium capitalize" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ health.status }}</span>
            </div>
          </div>
          <div>
            <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Version</p>
            <p class="text-sm font-mono" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ version || health.version }}</p>
          </div>
          <div>
            <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Uptime</p>
            <p class="text-sm" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ formatUptime(health.uptimeInSeconds) }}</p>
          </div>
          <div>
            <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Last Checked</p>
            <p class="text-sm" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ new Date(health.timestamp).toLocaleTimeString() }}</p>
          </div>
        </div>

        <div v-else class="text-center py-6">
          <p class="text-sm" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Unable to reach server</p>
        </div>
      </div>

      <!-- ─── Scenarios ───────────────────────────────────── -->
      <div class="rounded-xl border p-5 transition-colors" :class="sectionClasses">
        <div class="flex items-center justify-between mb-4">
          <div class="flex items-center gap-2">
            <PlayIcon class="w-4 h-4" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
            <h2 class="text-sm font-semibold" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Scenarios</h2>
          </div>
          <button @click="handleResetScenarios"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
            :class="isDark ? 'bg-emerald-600 hover:bg-emerald-500 text-white' : 'bg-emerald-600 hover:bg-emerald-700 text-white'">
            <ArrowPathIcon class="w-3.5 h-3.5" />
            Reset All
          </button>
        </div>

        <!-- Description -->
        <p class="text-xs mb-4" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
          These are WireMock stateful scenarios that track test workflows.
        </p>

        <div v-if="loadingScenarios" class="flex justify-center py-6">
          <BaseSpinner />
        </div>

        <div v-else-if="scenarios.length === 0" class="text-center py-8">
          <p class="text-sm" :class="isDark ? 'text-gray-400' : 'text-gray-500'">No scenarios defined</p>
        </div>

        <div v-else class="space-y-2">
          <div v-for="scenario in scenarios" :key="scenario.id"
            class="rounded-lg border p-4 transition-colors"
            :class="isDark ? 'bg-gray-800/50 border-gray-700 hover:bg-gray-900' : 'bg-gray-50 border-gray-200 hover:bg-gray-100'">
            <div class="flex items-center justify-between">
              <div>
                <p class="font-medium text-sm" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ scenario.name }}</p>
                <p class="text-xs mt-0.5" :class="isDark ? 'text-gray-500' : 'text-gray-400'">ID: {{ scenario.id }}</p>
              </div>
              <div class="flex items-center gap-2">
                <!-- Inline editing -->
                <template v-if="editingScenario === scenario.name">
                  <select v-model="editingState"
                    class="text-xs rounded border px-2 py-1 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    :class="isDark ? 'bg-gray-700 border-gray-600 text-gray-200' : 'bg-white border-gray-300 text-gray-700'">
                    <option v-for="s in scenario.possibleStates" :key="s" :value="s">{{ s }}</option>
                  </select>
                  <button @click="saveScenarioState(scenario)"
                    class="p-1 rounded transition-colors cursor-pointer"
                    :class="isDark ? 'text-emerald-400 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'">
                    <CheckIcon class="w-4 h-4" />
                  </button>
                  <button @click="cancelEditScenario"
                    class="p-1 rounded transition-colors cursor-pointer"
                    :class="isDark ? 'text-gray-400 hover:bg-gray-700' : 'text-gray-500 hover:bg-gray-100'">
                    <XMarkIcon class="w-4 h-4" />
                  </button>
                </template>
                <template v-else>
                  <span class="px-2 py-0.5 text-xs font-medium rounded-full"
                    :class="scenario.state === 'Started'
                      ? (isDark ? 'bg-green-500/20 text-green-300' : 'bg-green-100 text-green-700')
                      : (isDark ? 'bg-yellow-500/20 text-yellow-300' : 'bg-yellow-100 text-yellow-700')">
                    {{ scenario.state }}
                  </span>
                  <button v-if="scenario.possibleStates.length > 1"
                    @click="startEditScenario(scenario)"
                    class="p-1 rounded transition-colors cursor-pointer"
                    :class="isDark ? 'text-gray-500 hover:text-gray-300 hover:bg-gray-700' : 'text-gray-400 hover:text-gray-600 hover:bg-gray-100'"
                    title="Edit state">
                    <PencilSquareIcon class="w-3.5 h-3.5" />
                  </button>
                </template>
              </div>
            </div>
            <div v-if="scenario.possibleStates.length > 0" class="mt-2">
              <p class="text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                States: {{ scenario.possibleStates.join(' → ') }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- ─── Recording ─────────────────────────────────────── -->
      <div class="rounded-xl border p-5 transition-colors" :class="sectionClasses">
        <div class="flex items-center justify-between mb-4">
          <div class="flex items-center gap-2">
            <SignalIcon class="w-4 h-4" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
            <h2 class="text-sm font-semibold" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Traffic Recording</h2>
          </div>
          <span class="px-2 py-0.5 text-xs font-medium rounded-full"
            :class="recordingStatus === 'Recording'
              ? (isDark ? 'bg-red-500/20 text-red-300' : 'bg-red-100 text-red-700')
              : recordingStatus === 'Stopped'
                ? (isDark ? 'bg-yellow-500/20 text-yellow-300' : 'bg-yellow-100 text-yellow-700')
                : (isDark ? 'bg-gray-500/20 text-gray-400' : 'bg-gray-100 text-gray-500')">
            {{ recordingStatus === 'NeverStarted' ? 'Idle' : recordingStatus }}
          </span>
        </div>

        <p class="text-xs mb-4" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
          Record real API traffic as replayable stubs. Enter the target base URL and start recording.
        </p>

        <div class="space-y-4">
          <div>
            <label class="block text-xs font-medium mb-1" :class="labelClasses">Target Base URL</label>
            <input v-model="recordingTargetUrl" type="text"
              placeholder="https://api.example.com"
              :disabled="recordingStatus === 'Recording'"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
              :class="[
                inputClasses,
                recordingStatus === 'Recording' ? 'opacity-50 cursor-not-allowed' : ''
              ]" />
          </div>

          <div class="flex flex-wrap items-center gap-2">
            <button v-if="recordingStatus !== 'Recording'"
              @click="handleStartRecording"
              :disabled="recordingLoading || !recordingTargetUrl.trim()"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white transition-colors cursor-pointer"
              :class="recordingLoading || !recordingTargetUrl.trim()
                ? 'bg-emerald-400 cursor-not-allowed'
                : 'bg-emerald-600 hover:bg-emerald-500'">
              <PlayIcon class="w-3.5 h-3.5" />
              {{ recordingLoading ? 'Starting...' : 'Start Recording' }}
            </button>
            <button v-if="recordingStatus === 'Recording'"
              @click="handleStopRecording"
              :disabled="recordingLoading"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white transition-colors cursor-pointer"
              :class="recordingLoading ? 'bg-red-400 cursor-not-allowed' : 'bg-red-600 hover:bg-red-500'">
              <StopIcon class="w-3.5 h-3.5" />
              {{ recordingLoading ? 'Stopping...' : 'Stop Recording' }}
            </button>
            <button v-if="recordingStatus === 'Recording'"
              @click="handleSnapshot"
              :disabled="snapshotLoading"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark
                ? 'bg-gray-700 hover:bg-gray-600 text-gray-200'
                : 'bg-gray-200 hover:bg-gray-300 text-gray-700'">
              <CameraIcon class="w-3.5 h-3.5" />
              {{ snapshotLoading ? 'Taking...' : 'Snapshot' }}
            </button>
          </div>
        </div>
      </div>

      <!-- ─── Global Settings ─────────────────────────────── -->
      <div class="rounded-xl border p-5 transition-colors" :class="sectionClasses">
        <div class="flex items-center gap-2 mb-4">
          <CogIcon class="w-4 h-4" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
          <h2 class="text-sm font-semibold" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Global Response Delay</h2>
        </div>

        <!-- Description -->
        <p class="text-xs mb-4" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
          Applies a delay to all responses for simulating slow networks.
        </p>

        <div class="space-y-4">
          <div class="flex items-center gap-2">
            <input id="delayEnabled" v-model="delayEnabled" type="checkbox"
              class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
            <label for="delayEnabled" class="text-sm" :class="labelClasses">Enable global delay</label>
          </div>

          <div v-if="delayEnabled" class="space-y-4 pl-1">
            <div>
              <label class="block text-xs font-medium mb-1" :class="labelClasses">Delay Type</label>
              <select v-model="delayType"
                class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="inputClasses">
                <option value="fixed">Fixed</option>
                <option value="uniform">Uniform</option>
                <option value="lognormal">Log-normal</option>
              </select>
            </div>

            <div v-if="delayType === 'fixed'">
              <label class="block text-xs font-medium mb-1" :class="labelClasses">
                <ClockIcon class="w-3.5 h-3.5 inline mr-1" />Delay (ms)
              </label>
              <input v-model.number="fixedDelay" type="number" min="0"
                class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="inputClasses" />
            </div>

            <div v-if="delayType === 'uniform'" class="grid grid-cols-2 gap-4 max-w-sm">
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Lower (ms)</label>
                <input v-model.number="uniformLower" type="number" min="0"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="inputClasses" />
              </div>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Upper (ms)</label>
                <input v-model.number="uniformUpper" type="number" min="0"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="inputClasses" />
              </div>
            </div>

            <div v-if="delayType === 'lognormal'" class="grid grid-cols-2 gap-4 max-w-sm">
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Median (ms)</label>
                <input v-model.number="lognormalMedian" type="number" min="0"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="inputClasses" />
              </div>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Sigma</label>
                <input v-model.number="lognormalSigma" type="number" min="0" step="0.01"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="inputClasses" />
              </div>
            </div>
          </div>

          <BaseButton
            description="Save Settings"
            :color="BaseButtonEnum.GREEN"
            :is-loading="settingsSaving"
            @click="handleSaveSettings"
          />
        </div>
      </div>

      <!-- ─── Danger Zone ─────────────────────────────────── -->
      <div class="rounded-xl border-2 p-5 transition-colors"
        :class="isDark ? 'border-red-500/30 bg-red-500/5' : 'border-red-200 bg-red-50/50'">
        <div class="flex items-center gap-2 mb-4">
          <ExclamationTriangleIcon class="w-4 h-4 text-red-500" />
          <h2 class="text-sm font-semibold text-red-600">Danger Zone</h2>
        </div>

        <!-- Description -->
        <p class="text-xs mb-4 text-red-600">
          These are destructive operations.
        </p>

        <div class="space-y-4">
          <!-- Reset All -->
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div>
              <p class="text-sm font-medium" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Reset All</p>
              <p class="text-xs" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Reset all stub mappings to defaults and clear the request journal</p>
            </div>
            <div v-if="!confirmReset" class="shrink-0">
              <BaseButton description="Reset" :color="BaseButtonEnum.RED" @click="confirmReset = true" />
            </div>
            <div v-else class="flex flex-wrap items-center gap-2 shrink-0">
              <span class="text-xs text-red-500 font-medium">Are you sure?</span>
              <BaseButton description="Confirm" :color="BaseButtonEnum.RED" @click="handleResetAll" />
              <BaseButton description="Cancel" :color="BaseButtonEnum.WHITE" @click="confirmReset = false" />
            </div>
          </div>

          <div class="border-t" :class="isDark ? 'border-red-500/20' : 'border-red-200'" />

          <!-- Shutdown -->
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div>
              <p class="text-sm font-medium" :class="isDark ? 'text-gray-200' : 'text-gray-700'">Shutdown Server</p>
              <p class="text-xs" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Gracefully terminate the WireMock server process</p>
            </div>
            <div v-if="!confirmShutdown" class="shrink-0">
              <BaseButton description="Shutdown" :color="BaseButtonEnum.RED" @click="confirmShutdown = true" />
            </div>
            <div v-else class="flex flex-wrap items-center gap-2 shrink-0">
              <span class="text-xs text-red-500 font-medium">This cannot be undone!</span>
              <BaseButton description="Yes, Shutdown" :color="BaseButtonEnum.RED" :is-loading="shutdownLoading" @click="handleShutdown" />
              <BaseButton description="Cancel" :color="BaseButtonEnum.WHITE" @click="confirmShutdown = false" />
            </div>
          </div>
        </div>
      </div>

      <!-- Toast -->
      <BaseToast v-if="toast.show" :description="toast.message" :mode="toast.mode" />
    </div>
  </div>
</template>