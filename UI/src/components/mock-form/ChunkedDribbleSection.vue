<template>
  <BaseCollapsibleSection
    title="Chunked Dribble Delay"
    :collapsed="collapsed"
    :badge="enabled ? 'Active' : null"
    @toggle="emit('toggle')"
  >
    <p class="text-xs mb-4" :class="t.dimText">
      Trickle response bytes over time by splitting the body into chunks.
    </p>
    <div class="flex items-center gap-2 mb-4">
      <input
        id="enableChunkedDribble"
        v-model="enabledModel"
        type="checkbox"
        :disabled="faultEnabled"
        class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed"
      />
      <label
        for="enableChunkedDribble"
        class="text-sm"
        :class="[labelClasses, faultEnabled ? 'opacity-50 cursor-not-allowed' : '']"
      >
        Enable chunked dribble delay
      </label>
    </div>
    <div
      v-if="faultEnabled"
      class="flex items-start gap-2 text-xs mb-3 rounded-lg border px-3 py-2"
      :class="isDark ? 'bg-amber-500/10 border-amber-500/30 text-amber-300' : 'bg-amber-50 border-amber-200 text-amber-700'"
    >
      <ExclamationTriangleIcon class="w-4 h-4 shrink-0 mt-px" />
      <span>Chunked dribble is ignored while Fault Simulation is active.</span>
    </div>
    <div v-if="enabled" class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div>
        <label class="block text-sm font-medium mb-1" :class="labelClasses">
          Number of Chunks
        </label>
        <input
          v-model.number="numberOfChunksModel"
          type="number"
          min="1"
          class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
          :class="inputClasses"
        />
        <p
          v-if="active && bytesPerChunk !== null"
          class="text-xs mt-1"
          :class="t.dimText"
        >
          &asymp; {{ bytesPerChunk }} byte{{ bytesPerChunk === 1 ? '' : 's' }} per chunk
        </p>
      </div>
      <div>
        <label class="block text-sm font-medium mb-1" :class="labelClasses">
          Total Duration (ms)
        </label>
        <input
          v-model.number="totalDurationModel"
          type="number"
          min="1"
          class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
          :class="inputClasses"
        />
        <p v-if="formattedSeconds" class="text-xs mt-1" :class="t.dimText">
          {{ formattedSeconds }}<span v-if="active && msPerChunk !== null"> &middot; &asymp; {{ msPerChunk }} ms per chunk</span>
        </p>
      </div>
    </div>
    <p v-if="dribbleNoBody" class="text-xs mt-3" :class="t.amberText">
      Chunked dribble has no effect without a response body.
    </p>
  </BaseCollapsibleSection>
</template>

<script setup lang="ts">
/**
 * ChunkedDribbleSection — trickles a response body out in N chunks over
 * a total duration. Inactive when Fault Simulation is on (WireMock
 * ignores delays in that mode).
 *
 * Live hint computations (bytes per chunk, ms per chunk, no-body
 * warning) used to live in CreateMock.vue. They're pure derivations of
 * the props, so they move here cleanly.
 */
import { computed } from 'vue'
import { BaseCollapsibleSection, useTheme, useThemeClasses } from 'mgv-backoffice'
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'

interface Props {
  collapsed: boolean
  enabled: boolean
  /**
   * Number of dribble chunks. Typed `number` to match the parent ref's
   * type, even though `<input type="number" v-model.number>` can yield
   * `NaN` for empty input — the downstream `Number.isFinite()` checks
   * cover that case.
   */
  numberOfChunks: number
  /** Total dribble duration in ms. Same NaN caveat as numberOfChunks. */
  totalDuration: number
  /** When true, fault sim has the floor — chunked dribble is bypassed. */
  faultEnabled: boolean
  /** Length of the response body, used to estimate bytes-per-chunk. 0 if empty. */
  responseBodyLength: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  toggle: []
  'update:enabled': [value: boolean]
  'update:numberOfChunks': [value: number]
  'update:totalDuration': [value: number]
}>()

const enabledModel = computed({
  get: () => props.enabled,
  set: (v) => emit('update:enabled', v),
})
const numberOfChunksModel = computed({
  get: () => props.numberOfChunks,
  set: (v) => emit('update:numberOfChunks', v),
})
const totalDurationModel = computed({
  get: () => props.totalDuration,
  set: (v) => emit('update:totalDuration', v),
})

const { isDark } = useTheme()
const t = useThemeClasses()

const labelClasses = computed(() =>
  isDark.value ? 'text-gray-300' : 'text-gray-600',
)
const inputClasses = computed(() =>
  isDark.value
    ? 'bg-gray-800 border-gray-600 text-gray-100 placeholder-gray-500 focus:ring-emerald-500 focus:border-emerald-500'
    : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500 focus:border-emerald-500',
)

// "Active" means the section will actually affect WireMock's response —
// the toggle is on AND fault simulation isn't pre-empting it.
const active = computed(() => props.enabled && !props.faultEnabled)

// ms per chunk, rounded to the nearest integer. Returns null when either
// input is invalid so the template can v-if away the hint cleanly.
const msPerChunk = computed<number | null>(() => {
  const chunks = props.numberOfChunks
  const total = props.totalDuration
  if (typeof chunks !== 'number' || !Number.isFinite(chunks) || chunks < 1) return null
  if (typeof total !== 'number' || !Number.isFinite(total) || total < 1) return null
  return Math.round(total / chunks)
})

// Approximate bytes per chunk. Uses the literal string length of the
// response body (accurate for ASCII, close approximation for UTF-8).
// Returns null when there's no body or chunk count is invalid.
const bytesPerChunk = computed<number | null>(() => {
  const chunks = props.numberOfChunks
  if (typeof chunks !== 'number' || !Number.isFinite(chunks) || chunks < 1) return null
  if (props.responseBodyLength === 0) return null
  return Math.max(1, Math.round(props.responseBodyLength / chunks))
})

// Warn when dribble is enabled but there's nothing to dribble — WireMock
// will emit the empty body with no throttling effect.
const dribbleNoBody = computed(
  () => active.value && props.responseBodyLength === 0,
)

const formattedSeconds = computed(() => {
  const ms = props.totalDuration
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return ''
  const seconds = ms / 1000
  // < 1s -> 3 decimals for precision (0.250 s). >=1s -> 2 decimals is plenty.
  const decimals = seconds < 1 ? 3 : 2
  return `= ${seconds.toFixed(decimals)} s`
})
</script>
