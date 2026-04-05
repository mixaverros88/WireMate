<template>
  <div class="min-h-screen" :class="isDark ? 'bg-gray-900' : 'bg-gray-50'">
    <!-- Header -->
    <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border-b">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 :class="isDark ? 'text-white' : 'text-gray-900'" class="text-3xl font-bold">
              Logs
            </h1>
            <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="mt-2">
              View all captured HTTP requests from WireMock
            </p>
          </div>
          <div class="flex items-center gap-3">
            <BaseButton
              :is-loading="isSearching || isLoading"
              description="Refresh"
              :color="BaseButtonEnum.GREEN"
              @click="handleRefresh"
            />
            <!-- Delete-all wipes the WireMock request journal in one shot
                 (DELETE /__admin/requests). Disabled when nothing is
                 loaded so the user can't fire a no-op against a possibly-
                 down admin port. Confirms before destroying the journal. -->
            <BaseButton
              :is-loading="isDeletingAll"
              :is-disable="requests.length === 0"
              description="Delete All"
              :color="BaseButtonEnum.RED"
              @click="openDeleteAllModal"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">

      <!-- Analytics Graphs -->
      <div v-if="requests.length > 0" class="mb-8">
        <div class="flex items-center justify-between mb-4">
          <div class="flex items-center gap-2">
            <h2 :class="isDark ? 'text-white' : 'text-gray-900'" class="text-lg font-semibold">
              Analytics
            </h2>
            <span
              v-if="isFilterActive"
              class="px-2 py-0.5 text-xs font-medium rounded-full"
              :class="isDark ? 'bg-emerald-900/50 text-emerald-300 border border-emerald-700' : 'bg-emerald-100 text-emerald-700 border border-emerald-200'"
              title="Charts and counts below reflect the current search/filter"
            >
              Filtered
            </span>
          </div>
          <button
            type="button"
            @click="showGraphs = !showGraphs"
            :class="isDark ? 'text-gray-400 hover:text-gray-200' : 'text-gray-600 hover:text-gray-800'"
            class="text-sm font-medium transition-colors duration-300 ease-in-out cursor-pointer"
          >
            {{ showGraphs ? 'Hide Charts' : 'Show Charts' }}
          </button>
        </div>
        <!-- Matched / Unmatched Stats -->
        <div v-if="showGraphs && (matchedCount !== null || unmatchedCount !== null)" class="grid grid-cols-2 sm:grid-cols-3 gap-4 mb-6">
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-4 text-center">
            <p :class="isDark ? 'text-gray-400' : 'text-gray-500'" class="text-xs font-semibold uppercase tracking-wide mb-1">Total</p>
            <p class="text-2xl font-bold text-emerald-600">{{ isFilterActive ? filteredRequests.length : totalRequests }}</p>
            <p v-if="isFilterActive" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs mt-1">
              of {{ totalRequests }}
            </p>
          </div>
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-4 text-center">
            <p :class="isDark ? 'text-gray-400' : 'text-gray-500'" class="text-xs font-semibold uppercase tracking-wide mb-1">Matched</p>
            <p class="text-2xl font-bold" :class="isDark ? 'text-blue-400' : 'text-blue-600'">{{ filteredMatchedCount ?? '—' }}</p>
            <p v-if="isFilterActive && matchedCount !== null" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs mt-1">
              of {{ matchedCount }}
            </p>
          </div>
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-4 text-center">
            <p :class="isDark ? 'text-gray-400' : 'text-gray-500'" class="text-xs font-semibold uppercase tracking-wide mb-1">Unmatched</p>
            <p class="text-2xl font-bold" :class="filteredUnmatchedCount && filteredUnmatchedCount > 0 ? (isDark ? 'text-amber-400' : 'text-amber-600') : (isDark ? 'text-gray-400' : 'text-gray-600')">{{ filteredUnmatchedCount ?? '—' }}</p>
            <p v-if="isFilterActive && unmatchedCount !== null" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs mt-1">
              of {{ unmatchedCount }}
            </p>
          </div>
        </div>

        <div v-if="showGraphs" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-6">
          <!-- Methods Distribution -->
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-5">
            <h3 :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-semibold uppercase tracking-wide mb-4">
              Methods Distribution
            </h3>
            <div class="h-56 flex items-center justify-center">
              <Doughnut :data="methodChartData" :options="doughnutOptions" />
            </div>
          </div>
          <!-- Status Codes Distribution -->
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-5">
            <h3 :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-semibold uppercase tracking-wide mb-4">
              Status Codes
            </h3>
            <div class="h-56 flex items-center justify-center">
              <Doughnut :data="statusChartData" :options="doughnutOptions" />
            </div>
          </div>
          <!-- Timeline -->
          <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-5 lg:col-span-1 md:col-span-2">
            <h3 :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-semibold uppercase tracking-wide mb-4">
              Requests Over Time
            </h3>
            <div class="h-56">
              <Bar :data="timelineChartData" :options="barOptions" />
            </div>
          </div>
        </div>
      </div>

      <!-- Unified Search & Filter Panel
           Merges the former "Search & Filter" quick row with the old
           "Advanced Search" collapsible into a single always-visible panel.

           Semantics:
             • URL match type 'contains'  → client-side filter over the
               already-loaded set (old quick-search behavior)
             • URL match type url/urlPath/urlPattern/urlPathPattern → goes
               into the RequestPattern sent to POST /__admin/requests/find
               when the user clicks Search.
             • Method feeds both the client-side filter (live) AND the
               server pattern on Search.
             • Status / Since remain client-side only (WireMock's /find
               accepts neither).
             • Headers / Body Patterns are server-side only, applied on
               Search click. -->
      <div class="mb-8">
        <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-6 mb-6">
          <div class="flex flex-col gap-5">
            <!-- Top row: stats + header -->
            <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div>
                <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm font-medium">
                  Total Requests
                </p>
                <p class="text-3xl font-bold text-emerald-600 mt-1">
                  {{ totalRequests }}
                </p>
              </div>
              <div class="flex items-center gap-2">
                <svg class="w-5 h-5 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <span :class="isDark ? 'text-white' : 'text-gray-900'" class="font-semibold text-sm">
                  Search &amp; Filter
                </span>
              </div>
            </div>

            <!-- Row 1: URL -->
            <div>
              <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                URL
              </label>
              <div class="flex flex-col sm:flex-row gap-2">
                <select
                  v-model="urlMatchType"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                  class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 sm:w-52"
                  title="How the URL value is matched. 'contains' filters the already-loaded list locally; the others are sent as WireMock matchers on Search."
                >
                  <option value="contains">contains (client)</option>
                  <option value="url">url (exact)</option>
                  <option value="urlPath">urlPath (path only)</option>
                  <option value="urlPattern">urlPattern (regex)</option>
                  <option value="urlPathPattern">urlPathPattern (path regex)</option>
                </select>
                <input
                  v-model="urlValue"
                  type="text"
                  :placeholder="urlPlaceholder"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'"
                  class="flex-1 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  @keyup.enter="urlMatchType !== 'contains' && executeSearch()"
                />
              </div>
            </div>

            <!-- Row 2: Method / Since -->
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                  Method
                </label>
                <select
                  v-model="filterMethod"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                  class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500"
                >
                  <option value="">Any</option>
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                  <option value="HEAD">HEAD</option>
                </select>
              </div>
              <div>
                <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                  Since <span :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="font-normal normal-case">(client)</span>
                </label>
                <input
                  v-model="sinceFilter"
                  type="datetime-local"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                  class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500"
                />
              </div>
            </div>

            <!-- Row 3: Headers -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide">
                  Headers
                </label>
                <button
                  type="button"
                  @click="addHeaderFilter"
                  class="text-xs text-emerald-600 hover:text-emerald-500 font-medium cursor-pointer"
                >
                  + Add Header
                </button>
              </div>
              <div v-for="(h, idx) in headers" :key="idx" class="mb-2">
                <div class="flex flex-col sm:flex-row gap-2">
                  <input
                    v-model="h.key"
                    type="text"
                    placeholder="Header name *"
                    :class="[
                      headerKeyInvalid(h)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'),
                    ]"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono sm:w-40"
                  />
                  <select
                    v-model="h.matchType"
                    :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 sm:w-32"
                  >
                    <option value="equalTo">equalTo</option>
                    <option value="contains">contains</option>
                    <option value="matches">matches (regex)</option>
                  </select>
                  <input
                    v-model="h.value"
                    type="text"
                    placeholder="Value *"
                    :class="[
                      headerValueInvalid(h)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'),
                    ]"
                    class="flex-1 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  />
                  <button
                    type="button"
                    @click="headers.splice(idx, 1)"
                    class="p-2 rounded transition-colors duration-300 ease-in-out"
                    :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                    title="Remove"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
                <p v-if="headerRowError(h)" class="text-xs text-red-500 mt-1 ml-1">{{ headerRowError(h) }}</p>
              </div>
              <p v-if="headers.length === 0" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs italic">
                No header filters
              </p>
            </div>

            <!-- Row 4: Body Patterns -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide">
                  Request Body Patterns
                </label>
                <button
                  type="button"
                  @click="addBodyPattern"
                  class="text-xs text-emerald-600 hover:text-emerald-500 font-medium cursor-pointer"
                >
                  + Add Pattern
                </button>
              </div>
              <div v-for="(bp, idx) in bodyPatterns" :key="idx" class="mb-3">
                <div class="flex flex-col sm:flex-row gap-2">
                  <select
                    v-model="bp.type"
                    :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 sm:w-48"
                  >
                    <option value="equalToJson">equalToJson</option>
                    <option value="matchesJsonPath">matchesJsonPath</option>
                    <option value="contains">contains</option>
                    <option value="matches">matches (regex)</option>
                    <option value="equalTo">equalTo (exact)</option>
                  </select>
                  <button
                    type="button"
                    @click="bodyPatterns.splice(idx, 1)"
                    class="p-2 rounded transition-colors duration-300 ease-in-out self-start"
                    :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                    title="Remove"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
                <div class="mt-2">
                  <textarea
                    v-model="bp.value"
                    rows="3"
                    :placeholder="getBodyPatternPlaceholder(bp.type)"
                    :class="[
                      bodyPatternError(bp)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'),
                    ]"
                    class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  ></textarea>
                  <p v-if="bodyPatternError(bp)" class="text-xs text-red-500 mt-1">{{ bodyPatternError(bp) }}</p>
                  <!-- Extra options for equalToJson -->
                  <div v-if="bp.type === 'equalToJson'" class="flex gap-4 mt-2">
                    <label class="flex items-center gap-1.5 text-xs cursor-pointer" :class="isDark ? 'text-gray-400' : 'text-gray-600'">
                      <input v-model="bp.ignoreArrayOrder" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                      ignoreArrayOrder
                    </label>
                    <label class="flex items-center gap-1.5 text-xs cursor-pointer" :class="isDark ? 'text-gray-400' : 'text-gray-600'">
                      <input v-model="bp.ignoreExtraElements" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                      ignoreExtraElements
                    </label>
                  </div>
                </div>
              </div>
              <p v-if="bodyPatterns.length === 0" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs italic">
                No body pattern filters
              </p>
            </div>

            <!-- Row 5: Stub metadata filter (client-side).
                 Each row is a (key, value) pair; the filter passes when
                 the matched stub's metadata.<key> contains <value>
                 (case-insensitive substring on JSON-stringified values).
                 An entry with no matched stub fails as soon as any
                 metadata filter is set. -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-semibold uppercase tracking-wide">
                  Stub Metadata <span :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="font-normal normal-case">(client)</span>
                </label>
                <button
                  type="button"
                  @click="addMetadataFilter"
                  class="text-xs text-emerald-600 hover:text-emerald-500 font-medium cursor-pointer"
                >
                  + Add Metadata Filter
                </button>
              </div>
              <div v-for="(m, idx) in metadataFilters" :key="idx" class="mb-2">
                <div class="flex flex-col sm:flex-row gap-2">
                  <input
                    v-model="m.key"
                    type="text"
                    placeholder="metadata.key (e.g. projectId)"
                    :class="isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono sm:w-56"
                  />
                  <input
                    v-model="m.value"
                    type="text"
                    placeholder="contains value (blank = key must be present)"
                    :class="isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'"
                    class="flex-1 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  />
                  <button
                    type="button"
                    @click="metadataFilters.splice(idx, 1)"
                    class="p-2 rounded transition-colors duration-300 ease-in-out"
                    :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                    title="Remove"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              </div>
              <p v-if="metadataFilters.length === 0" :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs italic">
                No metadata filters
              </p>
            </div>

            <!-- Action buttons -->
            <div class="flex flex-wrap items-center gap-3 pt-3 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <BaseButton
                :is-loading="isSearching || isLoading"
                :is-disable="searchHasErrors"
                description="Search"
                :color="BaseButtonEnum.GREEN"
                @click="executeSearch"
              />
              <button
                type="button"
                :disabled="!isAnyInputSet"
                @click="resetSearch"
                :class="isDark
                  ? 'bg-gray-700 border-gray-600 text-gray-200 hover:bg-gray-600 disabled:opacity-50 disabled:cursor-not-allowed'
                  : 'bg-gray-50 border-gray-300 text-gray-700 hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed'"
                class="px-4 py-2 border rounded-lg text-sm font-medium cursor-pointer transition-colors duration-300 ease-in-out"
                title="Clear all fields and reload the full journal"
              >
                Reset
              </button>
              <!-- Remove matching: fires POST /__admin/requests/remove with
                   whatever pattern the form currently produces, then drops
                   the same entries client-side. Disabled when no pattern
                   is set OR when the result list is empty (nothing to
                   remove). Confirmation modal is required because this
                   mutates the journal. -->
              <button
                type="button"
                :disabled="filteredRequests.length === 0 || isRemovingMatching"
                @click="openRemoveMatchingModal"
                :class="isDark
                  ? 'bg-red-900/40 border-red-700 text-red-200 hover:bg-red-900/60 disabled:opacity-50 disabled:cursor-not-allowed'
                  : 'bg-red-50 border-red-300 text-red-700 hover:bg-red-100 disabled:opacity-50 disabled:cursor-not-allowed'"
                class="px-4 py-2 border rounded-lg text-sm font-medium cursor-pointer transition-colors duration-300 ease-in-out"
                title="Remove every journal entry that matches the current filters"
              >
                {{ isRemovingMatching ? 'Removing…' : `Remove ${filteredRequests.length} matching` }}
              </button>
              <span v-if="searchError" class="text-xs text-red-500 ml-2">{{ searchError }}</span>
              <p :class="isDark ? 'text-gray-500' : 'text-gray-400'" class="text-xs ml-auto">
                Click <span class="font-medium">Search</span> to apply server-side filters (URL regex, headers, body).
                <span class="font-medium">contains</span>, Since, and Stub Metadata filter live on the loaded list.
              </p>
            </div>
          </div>
        </div>

        <div :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm">
          Showing {{ filteredRequests.length }} of {{ totalRequests }} loaded requests
        </div>
      </div>

      <!-- Loading State -->
      <div v-if="isLoading" class="flex justify-center items-center py-12">
        <BaseSpinner />
      </div>

      <!-- Empty State -->
      <div v-else-if="filteredRequests.length === 0" :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-12 text-center">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <h3 :class="isDark ? 'text-white' : 'text-gray-900'" class="mt-4 text-lg font-medium">
          {{ totalRequests === 0 ? 'No requests captured' : 'No requests match your filters' }}
        </h3>
        <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="mt-2">
          {{ totalRequests === 0 ? 'Make some API requests to WireMock to see them here' : 'Try adjusting your search or filter' }}
        </p>
      </div>

      <!-- Requests List -->
      <div v-else class="space-y-4">
        <div
          v-for="request in filteredRequests"
          :key="request.id"
          :class="isDark ? 'bg-gray-800 border-gray-700 hover:bg-gray-900' : 'bg-white border-gray-200 hover:bg-gray-100'"
          class="border rounded-lg p-6 hover:shadow-lg transition-all duration-300 ease-in-out cursor-pointer"
          @click="viewRequest(request)"
        >
          <!-- Request Header -->
          <div class="flex items-start justify-between mb-4">
            <div class="flex-1">
              <div class="flex items-center gap-2 mb-2">
                <span :class="getMethodBgColor(request.request.method)" class="px-2 py-1 rounded text-xs font-bold text-white">
                  {{ request.request.method || 'ANY' }}
                </span>
                <span v-if="request.responseDefinition" :class="getStatusCodeColor(request.responseDefinition.status)" class="px-2 py-1 rounded text-xs font-bold text-white">
                  {{ request.responseDefinition.status }}
                </span>
              </div>
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm font-mono truncate break-all">
                {{ request.request.url }}
              </p>
            </div>
            <div class="flex items-start gap-1 ml-2">
              <button
                v-if="getEditableMockParams(request)"
                @click.stop="editMockFromRequest(request)"
                :aria-label="`Edit mock for ${request.request.url}`"
                class="p-1 rounded transition-colors duration-300 ease-in-out"
                :class="isDark ? 'text-emerald-400 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'"
                title="Edit this mock"
              >
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
              </button>
              <!-- Per-log delete (DELETE /__admin/requests/{id}). Stop
                   propagation so the row's "view detail" handler doesn't
                   fire when the user is targeting the delete icon. The
                   `find-…` prefix is a synthetic id we generate for older
                   WireMock versions that return flat LoggedRequestDetail
                   without an id — those rows can't be deleted server-
                   side, so we hide the button for them rather than fire
                   a doomed DELETE. -->
              <button
                v-if="request.id && !request.id.startsWith('find-')"
                @click.stop="openDeleteRequestModal(request)"
                :disabled="deletingIds.has(request.id)"
                :aria-label="`Delete request ${request.request.url}`"
                class="p-1 rounded transition-colors duration-300 ease-in-out disabled:opacity-50 disabled:cursor-not-allowed"
                :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                title="Delete this request"
              >
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </button>
            </div>
          </div>

          <!-- Request Details -->
          <div :class="isDark ? 'bg-gray-900' : 'bg-gray-100'" class="rounded p-3">
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
              <div>
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="uppercase tracking-wide font-medium">Client IP</p>
                <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.clientIp }}</p>
              </div>
              <div>
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="uppercase tracking-wide font-medium">Scheme</p>
                <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.scheme }}</p>
              </div>
              <div>
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="uppercase tracking-wide font-medium">Host</p>
                <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.host }}</p>
              </div>
              <div>
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="uppercase tracking-wide font-medium">Port</p>
                <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.port }}</p>
              </div>
              <div class="sm:col-span-2">
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="uppercase tracking-wide font-medium">Logged</p>
                <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="mt-1">{{ formatDate(request.request.loggedDate) }}</p>
              </div>
            </div>
          </div>
        </div>

      </div>
    </div>

    <!-- Detail Modal — receives the full LoggedRequest object so it can
         render without re-fetching (the page no longer calls
         GET /__admin/requests/{id}). -->
    <RequestDetailModal
      v-if="selectedRequest"
      :request="selectedRequest"
      @close="selectedRequest = null"
    />

    <!-- Delete-all confirmation. Uses the same ConfirmModal component the
         rest of the app uses for destructive actions. The modal's @cancel
         is also fired on Esc/backdrop-click; we no-op while a delete is in
         flight so the user can't dismiss the modal mid-request. -->
    <ConfirmModal
      v-if="showDeleteAllModal"
      :title="`Delete all ${totalRequests} request${totalRequests === 1 ? '' : 's'}?`"
      :message="`This will clear WireMock's request journal entirely. This action cannot be undone.`"
      :confirm-text="isDeletingAll ? 'Deleting…' : 'Yes, delete all'"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDeleteAll"
      @cancel="closeDeleteAllModal"
    />

    <!-- Per-row delete confirmation. Mirrors the Delete-All modal pattern but
         scoped to a single LoggedRequest. The trash icon on each card now
         opens this modal instead of firing DELETE /__admin/requests/{id}
         directly, so the user always gets an explicit confirmation step. -->
    <ConfirmModal
      v-if="requestToDelete"
      :title="`Delete this request?`"
      :message="deleteRequestMessage"
      :confirm-text="isDeletingSingle ? 'Deleting…' : 'Yes, delete'"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDeleteRequest"
      @cancel="closeDeleteRequestModal"
    />

    <!-- "Remove matching" confirmation. The message reminds the user
         that client-side-only filters (Since, Stub Metadata, contains
         URL) aren't sent to the server, so WireMock may delete more
         than the visible-row count if a server-side pattern is also
         set. -->
    <ConfirmModal
      v-if="showRemoveMatchingModal"
      :title="`Remove ${filteredRequests.length} matching request${filteredRequests.length === 1 ? '' : 's'}?`"
      :message="`These journal entries will be removed from WireMock. Note: the URL ‘contains’ search, Since, and Stub Metadata filters are applied client-side only — they aren't sent to WireMock, so the server-side pattern (URL regex, method, headers, body) is what's used for the actual delete.`"
      :confirm-text="isRemovingMatching ? 'Removing…' : 'Yes, remove'"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmRemoveMatching"
      @cancel="closeRemoveMatchingModal"
    />

    <!-- Toast Notifications -->
    <BaseToast
      v-if="showToast"
      :description="toastMessage"
      :mode="toastType"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BaseSpinner, BaseToast, BaseButton, BaseToastEnum, BaseButtonEnum } from 'mgv-backoffice'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { findRequests, getAllRequests, deleteAllRequests, deleteRequest, removeRequestsByPattern } from '../services/requestJournalService'
import RequestDetailModal from '../components/RequestDetailModal.vue'
import ConfirmModal from '../components/ConfirmModal.vue'
import type { LoggedRequest, RequestPattern } from '../types/requestJournal'
import { methodBadgeSolid, statusBadgeSolid } from '../utils/httpColors'

import {
  Chart as ChartJS,
  ArcElement,
  Tooltip,
  Legend,
  BarElement,
  CategoryScale,
  LinearScale,
} from 'chart.js'
import { Doughnut, Bar } from 'vue-chartjs'

ChartJS.register(ArcElement, Tooltip, Legend, BarElement, CategoryScale, LinearScale)

// Router & Theme
const { isDark } = useTheme()
const route = useRoute()
const router = useRouter()

// ── State ──────────────────────────────────────────────────────────────
//
// The Logs page only talks to WireMock via POST /__admin/requests/find, so
// there's no server-side pagination or dedicated unmatched endpoint to
// orchestrate — findRequests(pattern) returns the matching journal entries
// in one shot, and the UI narrows further from there.
//
// The search/filter UI is unified: one panel, with some fields that apply
// live on the client (contains URL, status, since) and others that get sent
// as a WireMock RequestPattern on the Search button click (url/urlPath/
// urlPattern/urlPathPattern, method, headers, body patterns).

const requests = ref<LoggedRequest[]>([])
const isLoading = ref(false)
const isSearching = ref(false)
const selectedRequest = ref<LoggedRequest | null>(null)
const showGraphs = ref(true)
const { showToast, toastMessage, toastType, showToastMessage } = useToast()

// URL search — single input; the match type decides whether it filters
// client-side (contains) or becomes a RequestPattern field on Search.
type UrlMatchType = 'contains' | 'url' | 'urlPath' | 'urlPattern' | 'urlPathPattern'
function parseUrlMatchType(raw: unknown): UrlMatchType {
  if (raw === 'url' || raw === 'urlPath' || raw === 'urlPattern' || raw === 'urlPathPattern') {
    return raw
  }
  return 'contains'
}
const urlMatchType = ref<UrlMatchType>(parseUrlMatchType(route.query.urlMatch))
const urlValue = ref(typeof route.query.search === 'string' ? route.query.search : '')

// Method / since — method applies both client-side (live) AND server-side
// on Search; since is strictly client-side.
const filterMethod = ref(typeof route.query.method === 'string' ? route.query.method : '')
const sinceFilter = ref('')

// Server-side-only pattern inputs
interface HeaderFilter {
  key: string
  matchType: 'equalTo' | 'contains' | 'matches'
  value: string
}
interface BodyPatternFilter {
  type: 'equalToJson' | 'matchesJsonPath' | 'contains' | 'matches' | 'equalTo'
  value: string
  ignoreArrayOrder: boolean
  ignoreExtraElements: boolean
}
const headers = ref<HeaderFilter[]>([])
const bodyPatterns = ref<BodyPatternFilter[]>([])
const searchError = ref('')

// Metadata-based filter — operates on the *matched stub's* metadata
// (req.stubMapping.metadata) since journal entries don't carry metadata
// of their own. WireMock's /find endpoint doesn't accept a metadata
// pattern either, so this is strictly client-side: each row says "the
// matched stub's metadata.<key> contains <value>" (case-insensitive
// substring on the JSON-stringified value, which is the most forgiving
// option for the mix of strings and structured values metadata can
// carry). Unmatched journal entries are excluded as soon as a metadata
// filter is set, since they have no stub metadata to compare against.
interface MetadataFilter {
  key: string
  value: string
}
const metadataFilters = ref<MetadataFilter[]>([])

function addMetadataFilter() {
  metadataFilters.value.push({ key: '', value: '' })
}

// ── Computed ───────────────────────────────────────────────────────────

const totalRequests = computed(() => requests.value.length)

// True when the user has filled in anything at all — controls the Reset
// button's enabled state.
const isAnyInputSet = computed(() => {
  return urlValue.value.trim() !== ''
    || filterMethod.value !== ''
    || sinceFilter.value !== ''
    || headers.value.length > 0
    || bodyPatterns.value.length > 0
    || metadataFilters.value.length > 0
})

// Active (non-blank) metadata filters. Drawn from the form rows but
// trimmed and dropped when both fields are empty so a half-filled row
// never narrows the list while the user is mid-type.
const activeMetadataFilters = computed<MetadataFilter[]>(() => {
  return metadataFilters.value
    .map(m => ({ key: m.key.trim(), value: m.value.trim() }))
    .filter(m => m.key.length > 0)
})

// Stringify a metadata value the same way the user is likely to think
// about it: literal strings stay as-is, structured values get
// JSON.stringify'd. Used by the contains-match below.
function metadataValueAsString(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

// True when the matched stub's metadata satisfies every active
// metadata filter row. An unmatched journal entry (no stubMapping)
// fails as soon as any metadata filter is set — there's nothing to
// match against.
function metadataMatches(req: LoggedRequest, filters: MetadataFilter[]): boolean {
  if (filters.length === 0) return true
  const md = req.stubMapping?.metadata
  if (!md) return false
  for (const f of filters) {
    const raw = (md as Record<string, unknown>)[f.key]
    if (raw === undefined) return false
    if (!f.value) {
      // No value supplied — treat as "key must be present" (which we
      // just verified via the undefined check).
      continue
    }
    const haystack = metadataValueAsString(raw).toLowerCase()
    if (!haystack.includes(f.value.toLowerCase())) return false
  }
  return true
}

const urlPlaceholder = computed(() => {
  switch (urlMatchType.value) {
    case 'url': return '/api/endpoint?param=value'
    case 'urlPath': return '/api/endpoint'
    case 'urlPattern': return '/api/.*'
    case 'urlPathPattern': return '/api/users/[0-9]+'
    case 'contains':
    default:
      return 'Search by URL…'
  }
})

// Parses the datetime-local "since" input into epoch-ms. Returns null for
// empty / malformed values so the filter silently no-ops rather than hiding
// everything.
const sinceEpochMs = computed<number | null>(() => {
  if (!sinceFilter.value) return null
  const t = new Date(sinceFilter.value).getTime()
  return Number.isNaN(t) ? null : t
})

const filteredRequests = computed(() => {
  return requests.value.filter(req => {
    // URL "contains" is client-side. For all other match types, we trust
    // /find already filtered server-side and don't re-apply here (a regex
    // match in WireMock may include URLs that don't contain the raw
    // pattern string, so a client contains-check would over-filter).
    let matchesUrl = true
    if (urlMatchType.value === 'contains' && urlValue.value) {
      matchesUrl = req.request.url.toLowerCase().includes(urlValue.value.toLowerCase())
    }

    const matchesMethod = filterMethod.value === '' ||
      req.request.method === filterMethod.value

    // Client-side "since" filter — /find doesn't accept a since param, so we
    // apply it here over the already-loaded set. Entries without a parseable
    // loggedDate are kept to avoid silently hiding partial data.
    let matchesSince = true
    if (sinceEpochMs.value !== null) {
      const logged = req.request.loggedDate
      const loggedMs = logged ? new Date(logged).getTime() : NaN
      if (!Number.isNaN(loggedMs)) {
        matchesSince = loggedMs >= sinceEpochMs.value
      }
    }

    const matchesMetadata = metadataMatches(req, activeMetadataFilters.value)

    return matchesUrl && matchesMethod && matchesSince && matchesMetadata
  })
})

// True when any narrowing is in effect (either server pattern or client
// filter) — controls the "Filtered" badge and the "of N" hint.
const isFilterActive = computed(() => {
  const urlActive = urlValue.value.trim() !== ''
  return urlActive
    || filterMethod.value !== ''
    || sinceEpochMs.value !== null
    || headers.value.some(h => h.key && h.value)
    || bodyPatterns.value.some(bp => bp.value.trim())
    || activeMetadataFilters.value.length > 0
})

// Matched / unmatched counts are derived purely from the loaded list — there
// is no dedicated /unmatched call anymore. Entries where `wasMatched` is
// absent (e.g. flat LoggedRequestDetail shape from /find) are treated as
// unknown, so the tile shows "—" rather than a misleading zero.
function countByMatch(list: LoggedRequest[], matched: boolean): number | null {
  const withInfo = list.filter(r => r.wasMatched !== undefined)
  if (withInfo.length === 0) return null
  return withInfo.filter(r => r.wasMatched === matched).length
}

const matchedCount = computed<number | null>(() => countByMatch(requests.value, true))
const unmatchedCount = computed<number | null>(() => countByMatch(requests.value, false))

const filteredMatchedCount = computed<number | null>(() => {
  if (!isFilterActive.value) return matchedCount.value
  return countByMatch(filteredRequests.value, true)
})

const filteredUnmatchedCount = computed<number | null>(() => {
  if (!isFilterActive.value) return unmatchedCount.value
  return countByMatch(filteredRequests.value, false)
})

// ---- Chart Data ----

const METHOD_COLORS: Record<string, string> = {
  GET: '#2563eb',
  POST: '#059669',
  PUT: '#d97706',
  DELETE: '#dc2626',
  PATCH: '#9333ea',
  HEAD: '#6b7280',
  OPTIONS: '#0891b2',
}

const methodChartData = computed(() => {
  const counts: Record<string, number> = {}
  for (const req of filteredRequests.value) {
    const m = req.request.method || 'OTHER'
    counts[m] = (counts[m] || 0) + 1
  }
  const labels = Object.keys(counts)
  return {
    labels,
    datasets: [{
      data: labels.map(l => counts[l]),
      backgroundColor: labels.map(l => METHOD_COLORS[l] || '#6b7280'),
      borderWidth: 0,
    }],
  }
})

const statusChartData = computed(() => {
  const buckets: Record<string, number> = { '2xx': 0, '3xx': 0, '4xx': 0, '5xx': 0, 'N/A': 0 }
  for (const req of filteredRequests.value) {
    const status = req.responseDefinition?.status
    if (!status) { buckets['N/A']++; continue }
    if (status < 300) buckets['2xx']++
    else if (status < 400) buckets['3xx']++
    else if (status < 500) buckets['4xx']++
    else buckets['5xx']++
  }
  const filtered = Object.entries(buckets).filter(([, v]) => v > 0)
  const colorMap: Record<string, string> = {
    '2xx': '#059669',
    '3xx': '#2563eb',
    '4xx': '#d97706',
    '5xx': '#dc2626',
    'N/A': '#6b7280',
  }
  return {
    labels: filtered.map(([k]) => k),
    datasets: [{
      data: filtered.map(([, v]) => v),
      backgroundColor: filtered.map(([k]) => colorMap[k]),
      borderWidth: 0,
    }],
  }
})

const timelineChartData = computed(() => {
  // Group requests into time buckets (respects active search/filter)
  const sorted = [...filteredRequests.value]
    .filter(r => r.request.loggedDate)
    .sort((a, b) => new Date(a.request.loggedDate).getTime() - new Date(b.request.loggedDate).getTime())

  if (sorted.length === 0) {
    return { labels: [], datasets: [{ label: 'Requests', data: [], backgroundColor: '#059669' }] }
  }

  const first = new Date(sorted[0].request.loggedDate).getTime()
  const last = new Date(sorted[sorted.length - 1].request.loggedDate).getTime()
  const range = last - first

  // Decide bucket size based on time range
  let bucketMs: number
  let formatLabel: (d: Date) => string

  if (range < 1000 * 60 * 60) {
    // Less than 1 hour → bucket per minute
    bucketMs = 1000 * 60
    formatLabel = (d: Date) => `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
  } else if (range < 1000 * 60 * 60 * 24) {
    // Less than 1 day → bucket per hour
    bucketMs = 1000 * 60 * 60
    formatLabel = (d: Date) => `${d.getHours().toString().padStart(2, '0')}:00`
  } else if (range < 1000 * 60 * 60 * 24 * 30) {
    // Less than 30 days → bucket per day
    bucketMs = 1000 * 60 * 60 * 24
    formatLabel = (d: Date) => `${(d.getMonth() + 1).toString().padStart(2, '0')}/${d.getDate().toString().padStart(2, '0')}`
  } else {
    // More → bucket per week
    bucketMs = 1000 * 60 * 60 * 24 * 7
    formatLabel = (d: Date) => `${(d.getMonth() + 1).toString().padStart(2, '0')}/${d.getDate().toString().padStart(2, '0')}`
  }

  const buckets = new Map<number, number>()
  for (const req of sorted) {
    const t = new Date(req.request.loggedDate).getTime()
    const key = Math.floor(t / bucketMs) * bucketMs
    buckets.set(key, (buckets.get(key) || 0) + 1)
  }

  // Fill in gaps
  const bucketKeys = Array.from(buckets.keys()).sort((a, b) => a - b)
  if (bucketKeys.length > 1) {
    const start = bucketKeys[0]
    const end = bucketKeys[bucketKeys.length - 1]
    for (let t = start; t <= end; t += bucketMs) {
      if (!buckets.has(t)) buckets.set(t, 0)
    }
  }

  const sortedBuckets = Array.from(buckets.entries()).sort(([a], [b]) => a - b)
  // Cap at 30 buckets to keep the chart readable
  const display = sortedBuckets.length > 30
    ? sortedBuckets.slice(sortedBuckets.length - 30)
    : sortedBuckets

  return {
    labels: display.map(([ts]) => formatLabel(new Date(ts))),
    datasets: [{
      label: 'Requests',
      data: display.map(([, count]) => count),
      backgroundColor: '#059669',
      borderRadius: 4,
    }],
  }
})

const doughnutOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'bottom' as const,
      labels: {
        color: isDark.value ? '#d1d5db' : '#374151',
        padding: 12,
        font: { size: 11 },
      },
    },
  },
}))

const barOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { display: false },
  },
  scales: {
    x: {
      ticks: {
        color: isDark.value ? '#9ca3af' : '#6b7280',
        font: { size: 10 },
        maxRotation: 45,
      },
      grid: { display: false },
    },
    y: {
      beginAtZero: true,
      ticks: {
        color: isDark.value ? '#9ca3af' : '#6b7280',
        stepSize: 1,
      },
      grid: {
        color: isDark.value ? '#374151' : '#e5e7eb',
      },
    },
  },
}))

// ── Methods ────────────────────────────────────────────────────────────

const getMethodBgColor = methodBadgeSolid
const getStatusCodeColor = statusBadgeSolid

function formatDate(dateStr: string | number): string {
  try {
    return new Date(dateStr).toLocaleString()
  } catch {
    return String(dateStr)
  }
}

async function handleRefresh() {
  // Re-run the current search so Refresh behaves naturally whether the
  // user has server-side criteria set or is just viewing the full journal.
  await executeSearch()
}

// ── Journal mutations ──────────────────────────────────────────────────
//
// "Delete All" wipes WireMock's request journal in a single shot via
// DELETE /__admin/requests. Per-row delete uses
// DELETE /__admin/requests/{id}. Both update local state on success so the
// user doesn't need to wait for a refetch.
const isDeletingAll = ref(false)
const deletingIds = ref<Set<string>>(new Set())
// "Remove matching" confirmation + in-flight state. Driven by the same
// ConfirmModal pattern as Delete-All, but scoped to whatever pattern
// the search panel currently produces.
const showRemoveMatchingModal = ref(false)
const isRemovingMatching = ref(false)
// Modal-driven confirmation for Delete All. Mirrors the ConfirmModal usage
// elsewhere on the page (per-row delete in ProjectDetailView, etc.) — the
// page-wide modal is opened by setting `showDeleteAllModal` true; the
// modal's @confirm/@cancel events drive the rest.
const showDeleteAllModal = ref(false)

function openDeleteAllModal() {
  if (requests.value.length === 0) return
  showDeleteAllModal.value = true
}

function closeDeleteAllModal() {
  if (isDeletingAll.value) return
  showDeleteAllModal.value = false
}

async function confirmDeleteAll() {
  isDeletingAll.value = true
  try {
    await deleteAllRequests()
    requests.value = []
    showToastMessage('Request journal cleared', BaseToastEnum.SUCCESS)
    showDeleteAllModal.value = false
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to delete requests'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isDeletingAll.value = false
  }
}

// "Remove matching" — fires POST /__admin/requests/remove with the
// pattern the search panel built. Server-side patterns (URL regex,
// method, headers, body patterns) flow straight into the call;
// client-only filters (contains URL, since, metadata) don't have a
// server-side equivalent so they degrade to "best effort": we send the
// server pattern, then locally strip from `requests.value` whatever
// also satisfied the client filters. This means the journal on
// WireMock may still hold entries that *looked* filtered out in the UI,
// which is documented in the modal copy below.
async function confirmRemoveMatching() {
  const pattern = buildPattern()
  isRemovingMatching.value = true
  try {
    const result = await removeRequestsByPattern(pattern)
    const removedCount = (result.requestsRemoved?.length ?? result.ids.length) || 0
    // Drop the same entries from the local list so the UI doesn't show
    // ghost rows pending the next refresh. Match by id; entries with
    // synthetic `find-…` ids (rare path) just stay until the next reload.
    const removedIds = new Set(result.ids)
    if (removedIds.size > 0) {
      requests.value = requests.value.filter(r => !(r.id && removedIds.has(r.id)))
    }
    showToastMessage(
      removedCount > 0
        ? `Removed ${removedCount} matching request${removedCount === 1 ? '' : 's'}`
        : 'No matching requests to remove',
      BaseToastEnum.SUCCESS,
    )
    showRemoveMatchingModal.value = false
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to remove matching requests'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isRemovingMatching.value = false
  }
}

function openRemoveMatchingModal() {
  if (filteredRequests.value.length === 0) return
  showRemoveMatchingModal.value = true
}

function closeRemoveMatchingModal() {
  if (isRemovingMatching.value) return
  showRemoveMatchingModal.value = false
}

// Per-row delete is two-step: the trash icon opens a confirmation modal,
// then the modal's @confirm fires the actual DELETE. This mirrors how the
// rest of the app guards destructive actions (Delete-All here, project /
// mock deletions in ProjectsView and ProjectDetailView) so the user can't
// nuke an interesting request entry by misclick.
const requestToDelete = ref<LoggedRequest | null>(null)
const isDeletingSingle = ref(false)

const deleteRequestMessage = computed(() => {
  const req = requestToDelete.value
  if (!req) return ''
  const method = req.request.method || 'ANY'
  const url = req.request.url || ''
  return `${method} ${url} will be removed from the WireMock request journal. This action cannot be undone.`
})

function openDeleteRequestModal(req: LoggedRequest) {
  if (!req.id) return
  requestToDelete.value = req
}

function closeDeleteRequestModal() {
  // Don't allow dismissing while a delete is in flight so the modal can't
  // be torn down mid-request and lose the loading affordance.
  if (isDeletingSingle.value) return
  requestToDelete.value = null
}

async function confirmDeleteRequest() {
  const req = requestToDelete.value
  if (!req || !req.id) return
  // Track per-row pending state so the icon can spin without blocking the
  // rest of the list. Use a Set rather than a single ref so concurrent
  // deletes don't fight over the loading flag.
  deletingIds.value.add(req.id)
  // Trigger reactivity (Set mutations alone don't notify Vue).
  deletingIds.value = new Set(deletingIds.value)
  isDeletingSingle.value = true
  try {
    await deleteRequest(req.id)
    requests.value = requests.value.filter(r => r.id !== req.id)
    showToastMessage('Request deleted', BaseToastEnum.SUCCESS)
    requestToDelete.value = null
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to delete request'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    deletingIds.value.delete(req.id)
    deletingIds.value = new Set(deletingIds.value)
    isDeletingSingle.value = false
  }
}

// Runs a plain full-journal load (empty pattern → every request) and resets
// `requests`. Used by Reset and on initial mount.
async function loadRequests() {
  isLoading.value = true
  try {
    // Use GET /__admin/requests for the unfiltered load: it returns
    // wrapped ServeEvents with the real WireMock-assigned id, which is
    // what the per-row Delete button needs. Going through
    // findRequests({}) was returning flat LoggedRequestDetail entries on
    // this WireMock build - those have no id, so the trash icon was
    // hidden on every card.
    const response = await getAllRequests()
    requests.value = response.requests
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to load requests'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isLoading.value = false
  }
}

// ── Header / Body Pattern helpers ──────────────────────────────────────

function addHeaderFilter() {
  headers.value.push({ key: '', matchType: 'equalTo', value: '' })
}

function addBodyPattern() {
  bodyPatterns.value.push({ type: 'equalToJson', value: '', ignoreArrayOrder: true, ignoreExtraElements: true })
}

function headerKeyInvalid(h: HeaderFilter): boolean {
  return !h.key.trim() && !!h.value.trim()
}

function headerValueInvalid(h: HeaderFilter): boolean {
  return !!h.key.trim() && !h.value.trim()
}

function headerRowError(h: HeaderFilter): string {
  if (headerKeyInvalid(h)) return 'Header name is required when a value is provided'
  if (headerValueInvalid(h)) return 'Header value is required when a name is provided'
  return ''
}

function bodyPatternError(bp: BodyPatternFilter): string {
  const value = bp.value.trim()
  if (!value) return ''
  if (bp.type === 'equalToJson') {
    try {
      JSON.parse(value)
    } catch (e) {
      return `Invalid JSON: ${e instanceof Error ? e.message : 'parse error'}`
    }
  } else if (bp.type === 'matchesJsonPath') {
    if (!value.startsWith('$')) {
      return 'JSONPath expressions should start with $ (e.g. $.customerId)'
    }
  } else if (bp.type === 'matches') {
    try {
      new RegExp(value)
    } catch (e) {
      return `Invalid regex: ${e instanceof Error ? e.message : 'parse error'}`
    }
  }
  return ''
}

const searchHasErrors = computed(() => {
  if (headers.value.some(h => headerRowError(h))) return true
  if (bodyPatterns.value.some(bp => bodyPatternError(bp))) return true
  return false
})

function getBodyPatternPlaceholder(type: string): string {
  switch (type) {
    case 'equalToJson': return '{ "customerId": "12345" }'
    case 'matchesJsonPath': return '$.customerId'
    case 'contains': return 'some text in the body'
    case 'matches': return '.*customerId.*'
    case 'equalTo': return 'exact body content'
    default: return ''
  }
}

// ── Unified search ─────────────────────────────────────────────────────

function buildPattern(): RequestPattern {
  const pattern: RequestPattern = {}

  if (filterMethod.value) {
    pattern.method = filterMethod.value
  }

  if (urlMatchType.value !== 'contains' && urlValue.value.trim()) {
    const v = urlValue.value.trim()
    switch (urlMatchType.value) {
      case 'url': pattern.url = v; break
      case 'urlPath': pattern.urlPath = v; break
      case 'urlPattern': pattern.urlPattern = v; break
      case 'urlPathPattern': pattern.urlPathPattern = v; break
    }
  }

  const validHeaders = headers.value.filter(h => h.key && h.value)
  if (validHeaders.length > 0) {
    pattern.headers = {}
    for (const h of validHeaders) {
      pattern.headers[h.key] = { [h.matchType]: h.value }
    }
  }

  const validBody = bodyPatterns.value.filter(bp => bp.value.trim())
  if (validBody.length > 0) {
    pattern.bodyPatterns = validBody.map(bp => {
      if (bp.type === 'equalToJson') {
        let jsonValue: string | object = bp.value.trim()
        try {
          jsonValue = JSON.parse(bp.value.trim())
        } catch {
          // keep as string
        }
        const entry: Record<string, unknown> = { equalToJson: jsonValue }
        if (bp.ignoreArrayOrder) entry.ignoreArrayOrder = true
        if (bp.ignoreExtraElements) entry.ignoreExtraElements = true
        return entry as { equalToJson: string | object }
      } else if (bp.type === 'matchesJsonPath') {
        return { matchesJsonPath: bp.value.trim() }
      } else if (bp.type === 'contains') {
        return { contains: bp.value.trim() }
      } else if (bp.type === 'matches') {
        return { matches: bp.value.trim() }
      } else {
        return { equalTo: bp.value.trim() }
      }
    })
  }

  return pattern
}

async function executeSearch() {
  searchError.value = ''

  if (searchHasErrors.value) {
    searchError.value = 'Fix the highlighted errors before searching'
    return
  }

  const pattern = buildPattern()

  isSearching.value = true
  try {
    const hasServerPattern = Object.keys(pattern).length > 0
    const response = hasServerPattern
      ? await findRequests(pattern)
      : await getAllRequests()
    requests.value = response.requests
    if (hasServerPattern) {
      showToastMessage(
        `Found ${response.requests.length} matching request(s)`,
        BaseToastEnum.SUCCESS,
      )
    }
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Search failed'
    searchError.value = message
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isSearching.value = false
  }
}

function resetSearch() {
  urlMatchType.value = 'contains'
  urlValue.value = ''
  filterMethod.value = ''
  sinceFilter.value = ''
  headers.value = []
  bodyPatterns.value = []
  metadataFilters.value = []
  searchError.value = ''
  const query: Record<string, string> = { ...route.query } as Record<string, string>
  delete query.search
  delete query.urlMatch
  delete query.method
  delete query.status
  router.replace({ query })
  loadRequests()
}

function viewRequest(req: LoggedRequest) {
  selectedRequest.value = req
}

function getEditableMockParams(req: LoggedRequest): { projectId: string; mockId: string } | null {
  const stub = req.stubMapping
  if (!stub?.id) return null
  const projectId = stub.metadata?.projectId
  if (typeof projectId !== 'string' || !projectId) return null
  return { projectId, mockId: stub.id }
}

function editMockFromRequest(req: LoggedRequest) {
  const params = getEditableMockParams(req)
  if (!params) return
  router.push({ name: 'edit-mock', params: { id: params.projectId, mockId: params.mockId } })
}

watch([urlValue, urlMatchType, filterMethod], ([newUrl, newType, newMethod]) => {
  const query: Record<string, string> = { ...route.query } as Record<string, string>

  if (newUrl) { query.search = newUrl } else { delete query.search }
  if (newType && newType !== 'contains') { query.urlMatch = newType } else { delete query.urlMatch }
  if (newMethod) { query.method = newMethod } else { delete query.method }
  delete query.status

  const currentSearch = typeof route.query.search === 'string' ? route.query.search : ''
  const currentUrlMatch = typeof route.query.urlMatch === 'string' ? route.query.urlMatch : ''
  const currentMethod = typeof route.query.method === 'string' ? route.query.method : ''
  const currentStatus = typeof route.query.status === 'string' ? route.query.status : ''
  const desiredUrlMatch = (newType && newType !== 'contains') ? newType : ''
  if (
    currentSearch === (newUrl || '') &&
    currentUrlMatch === desiredUrlMatch &&
    currentMethod === (newMethod || '') &&
    currentStatus === ''
  ) {
    return
  }

  router.replace({ query })
})

watch(
  () => [route.query.search, route.query.urlMatch, route.query.method],
  ([newSearch, newUrlMatch, newMethod]) => {
    const search = typeof newSearch === 'string' ? newSearch : ''
    const match = parseUrlMatchType(newUrlMatch)
    const method = typeof newMethod === 'string' ? newMethod : ''
    if (urlValue.value !== search) urlValue.value = search
    if (urlMatchType.value !== match) urlMatchType.value = match
    if (filterMethod.value !== method) filterMethod.value = method
  }
)

onMounted(() => {
  if (
    (urlMatchType.value !== 'contains' && urlValue.value.trim()) ||
    filterMethod.value
  ) {
    executeSearch()
  } else {
    loadRequests()
  }
})
</script>
