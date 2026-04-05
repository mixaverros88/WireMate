<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import AppSidebar from './components/AppSidebar.vue'
import { useTheme } from './composables/useTheme'

const route = useRoute()
const { isDark } = useTheme()

const showSidebar = computed(() => route.name !== 'presentation')
</script>

<template>
  <div class="min-h-screen transition-colors duration-300"
    :class="isDark ? 'bg-gray-950 text-gray-100' : 'bg-gray-50 text-gray-800'">
    <!--
      Skip link: invisible until focused, then becomes the first tab stop so
      keyboard users can bypass the sidebar/top-bar and jump straight to the
      page content.
    -->
    <a
      href="#main-content"
      class="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-[100] focus:px-3 focus:py-2 focus:rounded-md focus:bg-emerald-600 focus:text-white focus:shadow-lg"
    >
      Skip to main content
    </a>
    <AppSidebar v-if="showSidebar" />
    <main id="main-content" tabindex="-1" :class="showSidebar ? 'pt-14 lg:pt-0 lg:ml-60' : ''">
      <RouterView />
    </main>
  </div>
</template>
