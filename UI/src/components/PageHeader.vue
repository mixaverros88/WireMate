<script setup lang="ts">
/**
 * Reusable page-level header.
 *
 * Standardises the "icon badge + title/subtitle on the left, action
 * buttons on the right" pattern first introduced on /notifications.
 * Used by /logs, /stubs and /near-misses so all four top-level views
 * share an identical header shape and width (max-w-4xl, matching the
 * notifications view).
 *
 * Slots:
 *   - `icon`   — the page-specific Heroicon component
 *   - `actions` — refresh / destructive buttons rendered on the right
 *
 * Props:
 *   - title       — the H1 text
 *   - subtitle    — the muted line below the title
 *   - iconColor   — controls the badge background + icon text colour
 *                   ('emerald' | 'sky' | 'red' | 'amber')
 */
import { computed } from 'vue'
import { useTheme, useThemeClasses } from 'mgv-backoffice'

type IconColor = 'emerald' | 'sky' | 'red' | 'amber'

const props = withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    iconColor?: IconColor
  }>(),
  {
    subtitle: '',
    iconColor: 'emerald',
  },
)

const { isDark } = useTheme()
const t = useThemeClasses()

// Maps the named colour palette to the background-badge classes used
// in dark/light themes. Keeping this in a single lookup means callers
// just pass `iconColor="sky"` and the component handles both modes.
const badgeBgClass = computed(() => {
  const palette: Record<IconColor, { dark: string; light: string }> = {
    emerald: { dark: 'bg-emerald-500/10', light: 'bg-emerald-50' },
    sky: { dark: 'bg-sky-500/10', light: 'bg-sky-50' },
    red: { dark: 'bg-red-500/10', light: 'bg-red-50' },
    amber: { dark: 'bg-amber-500/10', light: 'bg-amber-50' },
  }
  const entry = palette[props.iconColor]
  return isDark.value ? entry.dark : entry.light
})

// Icon text colour. Routed through the same name → palette mapping so
// callers don't have to know the exact Tailwind shade for each theme.
const iconTextClass = computed(() => {
  const palette: Record<IconColor, { dark: string; light: string }> = {
    emerald: { dark: 'text-emerald-400', light: 'text-emerald-600' },
    sky: { dark: 'text-sky-400', light: 'text-sky-600' },
    red: { dark: 'text-red-400', light: 'text-red-500' },
    amber: { dark: 'text-amber-400', light: 'text-amber-600' },
  }
  const entry = palette[props.iconColor]
  return isDark.value ? entry.dark : entry.light
})
</script>

<template>
  <!-- Width is fixed to max-w-4xl so /logs, /stubs, /near-misses all
       carry the same header footprint as /notifications, regardless
       of how wide their main content area is. -->
  <div class="max-w-4xl mx-auto px-4 sm:px-6">
    <div class="flex items-center justify-between mb-8">
      <div class="flex items-center gap-3">
        <div
          class="w-10 h-10 rounded-lg flex items-center justify-center"
          :class="badgeBgClass"
        >
          <slot name="icon" :icon-class="iconTextClass" />
        </div>
        <div>
          <h1 class="text-2xl font-bold" :class="t.primaryTextSoft">
            {{ title }}
          </h1>
          <p v-if="subtitle" class="text-sm" :class="t.dimTextAlt">
            {{ subtitle }}
          </p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>
