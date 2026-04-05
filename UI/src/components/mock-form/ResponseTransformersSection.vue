<template>
  <BaseCollapsibleSection
    title="Response Transformers"
    :collapsed="collapsed"
    @toggle="emit('toggle')"
  >
    <fieldset :disabled="faultEnabled" :class="faultEnabled ? 'opacity-50 pointer-events-none select-none' : ''">
      <p v-if="faultEnabled" class="text-xs mb-3" :class="th.amberText">
        Disabled while Fault Simulation is enabled — no response is emitted to transform.
      </p>
      <p class="text-xs mb-3" :class="th.dimText">
        Extension names applied to the response in order. Click a chip to add a known transformer, or type a custom one.
      </p>
      <!-- Quick-add chips for the well-known transformers -->
      <div class="flex flex-wrap gap-1.5 mb-2">
        <code
          v-for="name in COMMON_TRANSFORMERS"
          :key="name"
          class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
          :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
          :title="transformers.includes(name) ? 'Already added' : 'Click to add'"
          @click="addNamed(name)"
        >{{ name }}</code>
      </div>
      <!-- Free-text + selected-list -->
      <div class="flex gap-2 mb-2">
        <input
          v-model="inputModel"
          type="text"
          placeholder="Custom transformer name"
          class="flex-1 rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
          :class="inputClasses"
          @keyup.enter="addCustom"
        />
        <button
          type="button"
          class="px-3 py-2 text-xs font-medium rounded-lg cursor-pointer"
          :class="isDark ? 'text-emerald-400 hover:text-emerald-300 bg-gray-800' : 'text-emerald-600 hover:text-emerald-800 bg-gray-100'"
          @click="addCustom"
        >Add</button>
      </div>
      <div v-if="transformers.length > 0" class="flex flex-wrap gap-2 mb-2">
        <span
          v-for="(name, i) in transformers"
          :key="i"
          class="inline-flex items-center gap-1 text-xs px-2 py-1 rounded font-mono"
          :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-600'"
        >
          {{ name }}
          <button
            type="button"
            :aria-label="`Remove transformer ${name}`"
            class="text-red-400 hover:text-red-600"
            @click="removeAt(i)"
          >&times;</button>
        </span>
      </div>
      <p v-else class="text-xs italic mb-2" :class="th.dimText">
        No transformers — the response is served verbatim.
      </p>

      <!-- transformerParameters — opaque to the UI; we just shuttle the
           JSON map verbatim to whatever extension reads it. -->
      <div class="mt-3">
        <label class="block text-xs font-medium mb-1" :class="labelClasses">
          Transformer Parameters <span class="font-normal" :class="th.dimText">(optional JSON object)</span>
        </label>
        <textarea
          v-model="parametersJsonModel"
          rows="3"
          placeholder='{ "fieldName": "value", "templateUrl": "templates/foo.json.hbs" }'
          aria-label="Response transformer parameters JSON"
          class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
          :class="[inputClasses, parametersError ? 'border-red-400' : '']"
        />
        <p v-if="parametersError" class="text-xs text-red-500 mt-1">{{ parametersError }}</p>
        <p v-else class="text-xs mt-1" :class="th.dimText">
          Passed verbatim to the transformer extension. Leave blank if your transformers don't need parameters.
        </p>
      </div>
    </fieldset>
  </BaseCollapsibleSection>
</template>

<script setup lang="ts">
/**
 * ResponseTransformersSection — manages the list of WireMock response
 * transformer extension names plus an opaque JSON parameter blob.
 *
 * The transformers list is v-modelled as a `string[]` (we emit a new
 * array on every mutation so the parent ref's reactivity tracks
 * cleanly). The parameters JSON is v-modelled as a raw string so we
 * can show a live "invalid JSON" error without forcing the parent to
 * own a parse step.
 *
 * `parametersError` is re-derived here; the parent's payload builder
 * still does its own JSON.parse on save, so the error is purely a UX
 * helper.
 */
import { computed } from 'vue'
import { BaseCollapsibleSection, useTheme, useThemeClasses } from 'mgv-backoffice'
import { validateTransformerParameters } from '../../composables/useMockValidation'

interface Props {
  collapsed: boolean
  /** Currently selected transformer names, in order. */
  transformers: string[]
  /** Live text in the "custom name" input — v-modelled so the parent can clear on save. */
  input: string
  /** Raw textarea content for transformerParameters. */
  parametersJson: string
  /** When true, the section is fault-bypassed (no response emitted). */
  faultEnabled: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  toggle: []
  'update:transformers': [value: string[]]
  'update:input': [value: string]
  'update:parametersJson': [value: string]
}>()

// Well-known WireMock transformer extension names. Kept here because
// nothing else in the codebase consumes the list, and the section is
// the canonical place to document them.
const COMMON_TRANSFORMERS = [
  'response-template',
  'webhook-template',
] as const

const inputModel = computed({
  get: () => props.input,
  set: (v) => emit('update:input', v),
})
const parametersJsonModel = computed({
  get: () => props.parametersJson,
  set: (v) => emit('update:parametersJson', v),
})

function addNamed(name: string) {
  if (props.transformers.includes(name)) return
  emit('update:transformers', [...props.transformers, name])
}

function addCustom() {
  const trimmed = props.input.trim()
  if (!trimmed) return
  if (!props.transformers.includes(trimmed)) {
    emit('update:transformers', [...props.transformers, trimmed])
  }
  emit('update:input', '')
}

function removeAt(index: number) {
  const next = props.transformers.slice()
  next.splice(index, 1)
  emit('update:transformers', next)
}

// Live JSON validity check — delegates to the shared pure validator
// so the parent's form-level validation and this in-section feedback
// can never disagree.
const parametersError = computed<string>(() =>
  validateTransformerParameters(props.parametersJson),
)

// Note: `t` is the theme namespace — we rename to `th` here because
// the template's `v-for="name in COMMON_TRANSFORMERS"` used to bind
// over a variable named `t` which shadowed it.
const { isDark } = useTheme()
const th = useThemeClasses()

const labelClasses = computed(() =>
  isDark.value ? 'text-gray-300' : 'text-gray-600',
)
const inputClasses = computed(() =>
  isDark.value
    ? 'bg-gray-800 border-gray-600 text-gray-100 placeholder-gray-500 focus:ring-emerald-500 focus:border-emerald-500'
    : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500 focus:border-emerald-500',
)
</script>
