<template>
  <BaseCollapsibleSection
    title="Webhooks / Post-Serve Actions"
    :collapsed="collapsed"
    :badge="enabled ? 'Active' : null"
    @toggle="emit('toggle')"
  >
    <p class="text-xs mb-4" :class="t.dimText">
      Fire an HTTP callback after the response is sent.
    </p>
    <div class="flex items-center gap-2 mb-4">
      <input
        id="enableWebhook"
        v-model="enabledModel"
        type="checkbox"
        class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500"
      />
      <label for="enableWebhook" class="text-sm" :class="labelClasses">
        Enable webhook
      </label>
    </div>

    <div v-if="enabled" class="space-y-4">
      <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div class="md:col-span-1">
          <label class="block text-sm font-medium mb-1" :class="labelClasses">Method</label>
          <select
            v-model="methodModel"
            class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
            :class="inputClasses"
          >
            <option v-for="m in METHOD_OPTIONS" :key="m" :value="m">{{ m }}</option>
          </select>
        </div>
        <div class="md:col-span-3">
          <label
            class="block text-sm font-medium mb-1"
            :class="[labelClasses, (!url.trim() || urlError) ? t.redText : '']"
          >
            URL *
          </label>
          <input
            v-model="urlModel"
            type="text"
            placeholder="https://example.com/webhook"
            class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
            :class="urlError
              ? (isDark ? 'bg-gray-800 border-red-500 text-gray-100 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30' : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30')
              : requiredInputClasses(url.trim())"
          />
          <p v-if="urlError" class="text-xs text-red-500 mt-1">{{ urlError }}</p>
        </div>
      </div>

      <!-- Headers grid -->
      <div>
        <div class="flex items-center justify-between mb-2">
          <label class="block text-sm font-medium" :class="labelClasses">Headers</label>
          <button
            type="button"
            class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer"
            :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'"
            @click="addHeader"
          >+ Add</button>
        </div>
        <div v-for="(header, i) in headers" :key="i" class="mb-2">
          <div class="flex gap-2">
            <input
              v-model="header.key"
              placeholder="Header name"
              class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
              :class="isRowKeyMissing(header) ? requiredInputClasses('') : inputClasses"
            />
            <input
              v-model="header.value"
              placeholder="Header value"
              class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
              :class="isRowValueMissing(header) ? requiredInputClasses('') : inputClasses"
            />
            <button
              type="button"
              class="text-red-400 hover:text-red-600 px-2 text-lg"
              @click="removeHeader(i)"
            >&times;</button>
          </div>
          <p v-if="isRowValueMissing(header)" class="text-xs text-red-500 mt-1">
            Header value is required when a header name is provided.
          </p>
          <p v-if="isRowKeyMissing(header)" class="text-xs text-red-500 mt-1">
            Header name is required when a value is provided.
          </p>
        </div>
      </div>

      <div>
        <label class="block text-sm font-medium mb-1" :class="labelClasses">
          Body (supports Handlebars)
        </label>
        <textarea
          v-model="bodyModel"
          rows="6"
          :placeholder="bodyPlaceholder"
          class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
          :class="inputClasses"
        />
      </div>

      <div>
        <label class="block text-sm font-medium mb-1" :class="labelClasses">
          Delay before firing (ms)
        </label>
        <input
          v-model.number="delayModel"
          type="number"
          min="0"
          placeholder="0"
          class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
          :class="inputClasses"
        />
        <p v-if="formattedDelay" class="text-xs mt-1" :class="t.dimText">
          {{ formattedDelay }}
        </p>
      </div>
    </div>
  </BaseCollapsibleSection>
</template>

<script setup lang="ts">
/**
 * WebhooksSection — fires an HTTP callback after the matched response
 * is sent (WireMock's webhook post-serve action).
 *
 * Headers are an array of KeyValuePair. We keep the parent's source of
 * truth and only call .splice/.push on the array (Vue tracks deep
 * reactivity), but the v-model contract is "the parent owns the ref".
 */
import { computed } from 'vue'
import type { KeyValuePair } from '../../types/mock'
import { BaseCollapsibleSection, useTheme, useThemeClasses } from 'mgv-backoffice'
import { isValidAbsoluteUrl } from '../../composables/useMockValidation'

interface Props {
  collapsed: boolean
  enabled: boolean
  url: string
  method: string
  headers: KeyValuePair[]
  body: string
  delayMs: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  toggle: []
  'update:enabled': [value: boolean]
  'update:url': [value: string]
  'update:method': [value: string]
  'update:body': [value: string]
  'update:delayMs': [value: number]
}>()

const enabledModel = computed({
  get: () => props.enabled,
  set: (v) => emit('update:enabled', v),
})
const urlModel = computed({
  get: () => props.url,
  set: (v) => emit('update:url', v),
})
const methodModel = computed({
  get: () => props.method,
  set: (v) => emit('update:method', v),
})
const bodyModel = computed({
  get: () => props.body,
  set: (v) => emit('update:body', v),
})
const delayModel = computed({
  get: () => props.delayMs,
  set: (v) => emit('update:delayMs', v),
})

// Method whitelist: webhooks fire a concrete request, so 'ANY' isn't
// meaningful. Keep the list synchronized with the request matcher's set
// minus 'ANY' (the parent's HTTP_METHODS includes ANY because *matching*
// against any method makes sense).
const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS', 'TRACE'] as const

// Sample Handlebars-templated body shown as the textarea placeholder.
// Surfaces the three most useful WireMock template helpers users hit
// when wiring webhooks (jsonPath against the inbound body, `now`, and
// the request URL) so the syntax is discoverable without opening the
// docs. Pure visual hint — not auto-inserted as the value.
const bodyPlaceholder = `{
  "event": "stub-matched",
  "order": "{{jsonPath request.body '$.orderId'}}",
  "receivedAt": "{{now}}",
  "path": "{{request.url}}"
}`

function addHeader() {
  props.headers.push({ key: '', value: '' })
}
function removeHeader(i: number) {
  props.headers.splice(i, 1)
}

// Inline key/value row validators — same shape as the parent's helper
// for request-headers / query-params, duplicated here so the section
// stays standalone. The rules:
//   • Key present, value blank   → flag value-missing.
//   • Value present, key blank   → flag key-missing.
//   • Both blank or both present → no error.
function isRowKeyMissing(row: KeyValuePair): boolean {
  return Boolean((row.value ?? '').trim()) && !((row.key ?? '').trim())
}
function isRowValueMissing(row: KeyValuePair): boolean {
  return Boolean((row.key ?? '').trim()) && !((row.value ?? '').trim())
}

// Live URL feedback — empty is flagged by the form-level required-field
// validation; this only complains about *typed* URLs that fail the
// absolute-http(s) check.
const urlError = computed<string>(() => {
  if (!props.enabled) return ''
  const trimmed = props.url.trim()
  if (!trimmed) return ''
  return isValidAbsoluteUrl(trimmed)
    ? ''
    : 'Invalid URL. Must be an absolute http:// or https:// address (e.g. https://example.com/webhook).'
})

const formattedDelay = computed<string>(() => {
  const ms = props.delayMs
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return ''
  const seconds = ms / 1000
  const decimals = seconds < 1 ? 3 : 2
  return `= ${seconds.toFixed(decimals)} s`
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
function requiredInputClasses(value: string): string {
  if (!value) {
    return isDark.value
      ? 'bg-gray-800 border-red-500 text-gray-100 placeholder-gray-500 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30'
      : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30'
  }
  return inputClasses.value
}
</script>
