<template>
  <div class="min-h-screen" :class="t.pageBg">
    <div class="pt-8">
      <!-- Reusable PageHeader — same width (max-w-4xl) and shape as
           the one on /notifications. Pass the page-specific icon
           through the `icon` slot and the action buttons through
           `actions`. -->
      <PageHeader
        title="Logs"
        subtitle="View all captured HTTP requests from WireMock"
        icon-color="sky"
      >
        <template #icon="{ iconClass }">
          <DocumentTextIcon class="w-5 h-5" :class="iconClass" />
        </template>
        <template #actions>
          <!-- Refresh — labelled bordered button, kept visually consistent
               with the equivalent affordance in NotificationsView so the
               page-level refresh icon never changes shape between tabs. -->
          <button
            @click="handleRefresh"
            type="button"
            :disabled="isSearching || isLoading"
            class="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDark
              ? 'bg-gray-800 text-gray-100 border-gray-700 hover:bg-gray-700'
              : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-100'"
            title="Refresh"
          >
            <ArrowPathIcon class="w-4 h-4" :class="{ 'animate-spin': isSearching || isLoading }" />
            <span>Refresh</span>
          </button>
          <!-- Delete-all wipes the WireMock request journal in one shot
               (DELETE /__admin/requests). Disabled when nothing is
               loaded so the user can't fire a no-op against a possibly-
               down admin port. Confirms before destroying the journal. -->
          <button
            @click="openDeleteAllModal"
            type="button"
            :disabled="isDeletingAll || requests.length === 0"
            class="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDark
              ? 'bg-red-600/90 text-white border-red-700 hover:bg-red-600'
              : 'bg-red-600 text-white border-red-600 hover:bg-red-700'"
            title="Delete all requests"
          >
            <TrashIcon class="w-4 h-4" :class="{ 'animate-pulse': isDeletingAll }" />
            <span>Delete All</span>
          </button>
        </template>
      </PageHeader>
    </div>

    <!-- Main Content -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 pb-8">


      <!-- Analytics Graphs -->
      <div v-if="requests.length > 0" class="mb-8">
        <div class="flex items-center justify-between mb-4">
          <div class="flex items-center gap-2">
            <h2 :class="t.primaryText" class="text-lg font-semibold">
              Analytics
            </h2>
            <span
              v-if="isAnyInputSet"
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
        <!-- Matched / Unmatched Stats — counts now reflect the
             server-filtered set directly (the page does no client-side
             trimming any more, so what's on screen is what came back
             from WireMock). -->
        <div v-if="showGraphs && (matchedCount !== null || unmatchedCount !== null)" class="grid grid-cols-2 sm:grid-cols-3 gap-4 mb-6">
          <div class="border rounded-lg p-4 text-center"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <p :class="t.dimTextAlt" class="text-xs font-semibold uppercase tracking-wide mb-1">Total</p>
            <p class="text-2xl font-bold" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'">{{ totalRequests }}</p>
          </div>
          <div class="border rounded-lg p-4 text-center"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <p :class="t.dimTextAlt" class="text-xs font-semibold uppercase tracking-wide mb-1">Matched</p>
            <p class="text-2xl font-bold" :class="isDark ? 'text-blue-400' : 'text-blue-600'">{{ matchedCount ?? '—' }}</p>
          </div>
          <div class="border rounded-lg p-4 text-center"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <p :class="t.dimTextAlt" class="text-xs font-semibold uppercase tracking-wide mb-1">Unmatched</p>
            <p class="text-2xl font-bold" :class="unmatchedCount && unmatchedCount > 0 ? (t.amberText) : (t.mutedText)">{{ unmatchedCount ?? '—' }}</p>
          </div>
        </div>

        <div v-if="showGraphs" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-6">
          <!-- Methods Distribution -->
          <div class="border rounded-lg p-5"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <h3 :class="t.label" class="text-sm font-semibold uppercase tracking-wide mb-4">
              Methods Distribution
            </h3>
            <div class="h-56 flex items-center justify-center">
              <Doughnut :data="methodChartData" :options="doughnutOptions" />
            </div>
          </div>
          <!-- Status Codes Distribution -->
          <div class="border rounded-lg p-5"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <h3 :class="t.label" class="text-sm font-semibold uppercase tracking-wide mb-4">
              Status Codes
            </h3>
            <div class="h-56 flex items-center justify-center">
              <Doughnut :data="statusChartData" :options="doughnutOptions" />
            </div>
          </div>
          <!-- Timeline -->
          <div class="border rounded-lg p-5 lg:col-span-1 md:col-span-2"
            :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
            <h3 :class="t.label" class="text-sm font-semibold uppercase tracking-wide mb-4">
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
        <div class="border rounded-lg p-6 mb-6"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex flex-col gap-5">
            <!-- Row 1: URL -->
            <div>
              <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                URL
              </label>
              <!--
                Match-type dropdown + URL input always share a single
                row — no `flex-col` fallback, so even on narrow widths
                the dropdown sits directly to the left of the input
                rather than stacking above it. The input keeps its
                own `min-w-0` so flex doesn't blow the row out.
              -->
              <div class="flex flex-row gap-2">
                <!--
                  URL match selector — labels mirror the WireMock
                  /__admin/requests/find documentation 1:1 so users
                  recognise the field name they'd send in a raw curl:

                    contains          → client-side substring filter
                    url               → exact path + query string
                    urlPath           → path only (ignores query)
                    urlPattern        → regex on full URL incl. query
                    urlPathPattern    → regex on path only

                  Each value here corresponds to the matching
                  RequestPattern field WireMock accepts on /find.
                -->
                <select
                  v-model="urlMatchType"
                  :class="t.input"
                  class="shrink-0 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 w-64"
                  title="How the URL value is matched. 'contains' filters the already-loaded list locally; the others are sent as WireMock /find matchers on Search."
                >
                  <option value="contains">contains — substring (client)</option>
                  <option value="url">url — exact path + query</option>
                  <option value="urlPath">urlPath — path only (ignore query)</option>
                  <option value="urlPattern">urlPattern — regex (full URL)</option>
                  <option value="urlPathPattern">urlPathPattern — regex (path only)</option>
                </select>
                <input
                  v-model="urlValue"
                  type="text"
                  :placeholder="urlPlaceholder"
                  :class="t.input"
                  class="flex-1 min-w-0 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono transition-colors"
                  @keyup.enter="executeSearch()"
                />
              </div>
            </div>

            <!-- Row 2: Method / Since
                 Both server-side. Method becomes `method` on the /find
                 pattern (or "ANY" / omitted if Any is picked). Since
                 is sent as `?since=<ISO>` to GET /__admin/requests
                 when no /find pattern is needed. WireMock's /find
                 endpoint doesn't accept a `status` matcher, so we
                 don't expose one — every input on this panel goes to
                 the server. -->
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                  Method
                </label>
                <!--
                  Method dropdown — values match what WireMock's /find
                  accepts in the `method` field. "Any" is the empty
                  string (no `method` on the outgoing pattern);
                  selecting "ANY" explicitly sends `method: "ANY"` so
                  the request mirrors the curl in the WireMock
                  /requests/find docs.
                -->
                <select
                  v-model="filterMethod"
                  :class="t.input"
                  class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500"
                >
                  <option value="">Any (no filter)</option>
                  <option value="ANY">ANY (explicit)</option>
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                  <option value="HEAD">HEAD</option>
                  <option value="OPTIONS">OPTIONS</option>
                </select>
              </div>
              <div>
                <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide mb-2">
                  Since
                </label>
                <input
                  v-model="sinceFilter"
                  type="datetime-local"
                  :class="t.input"
                  class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500"
                  title="Sent server-side as the ?since=<ISO> query param on GET /__admin/requests so the journal is narrowed before download."
                />
              </div>
            </div>

            <!-- Row 3: Headers -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide">
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
              <div v-for="(h, idx) in headers" :key="h.id" class="mb-2">
                <div class="flex flex-col sm:flex-row gap-2">
                  <input
                    v-model="h.key"
                    type="text"
                    placeholder="Header name *"
                    :class="[
                      headerKeyInvalid(h)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (t.inputWithPlaceholder),
                    ]"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono sm:w-40"
                  />
                  <select
                    v-model="h.matchType"
                    :class="t.input"
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
                        : (t.inputWithPlaceholder),
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
              <p v-if="headers.length === 0" :class="t.dimText" class="text-xs italic">
                No header filters
              </p>
            </div>

            <!-- Row 3.5: Query Parameters
                 WireMock's /find pattern accepts a `queryParameters`
                 field (same matcher shape as headers). We didn't expose
                 it before, which forced users to encode the query in
                 the URL Regex; now they can match individual params
                 directly. -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide">
                  Query Parameters
                </label>
                <button
                  type="button"
                  @click="addQueryParamFilter"
                  class="text-xs text-emerald-600 hover:text-emerald-500 font-medium cursor-pointer"
                >
                  + Add Query Param
                </button>
              </div>
              <div v-for="(q, idx) in queryParamFilters" :key="q.id" class="mb-2">
                <div class="flex flex-col sm:flex-row gap-2">
                  <input
                    v-model="q.key"
                    type="text"
                    placeholder="Param name *"
                    :class="[
                      headerKeyInvalid(q)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (t.inputWithPlaceholder),
                    ]"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono sm:w-40"
                  />
                  <select
                    v-model="q.matchType"
                    :class="t.input"
                    class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 sm:w-32"
                  >
                    <option value="equalTo">equalTo</option>
                    <option value="contains">contains</option>
                    <option value="matches">matches (regex)</option>
                  </select>
                  <input
                    v-model="q.value"
                    type="text"
                    placeholder="Value *"
                    :class="[
                      headerValueInvalid(q)
                        ? (isDark ? 'bg-gray-700 border-red-500 text-white placeholder-gray-400 ring-1 ring-red-500/30' : 'bg-gray-50 border-red-400 text-gray-900 placeholder-gray-500 ring-1 ring-red-400/30')
                        : (t.inputWithPlaceholder),
                    ]"
                    class="flex-1 px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  />
                  <button
                    type="button"
                    @click="queryParamFilters.splice(idx, 1)"
                    class="p-2 rounded transition-colors duration-300 ease-in-out"
                    :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                    title="Remove"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
                <p v-if="headerRowError(q)" class="text-xs text-red-500 mt-1 ml-1">{{ headerRowError(q) }}</p>
              </div>
              <p v-if="queryParamFilters.length === 0" :class="t.dimText" class="text-xs italic">
                No query parameter filters
              </p>
            </div>

            <!-- Row 4: Body Patterns -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label :class="t.label" class="block text-xs font-semibold uppercase tracking-wide">
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
              <div v-for="(bp, idx) in bodyPatterns" :key="bp.id" class="mb-3">
                <div class="flex flex-col sm:flex-row gap-2">
                  <select
                    v-model="bp.type"
                    :class="t.input"
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
                        : (t.inputWithPlaceholder),
                    ]"
                    class="w-full px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 font-mono"
                  ></textarea>
                  <p v-if="bodyPatternError(bp)" class="text-xs text-red-500 mt-1">{{ bodyPatternError(bp) }}</p>
                  <!-- Extra options for equalToJson -->
                  <div v-if="bp.type === 'equalToJson'" class="flex gap-4 mt-2">
                    <label class="flex items-center gap-1.5 text-xs cursor-pointer" :class="t.mutedText">
                      <input v-model="bp.ignoreArrayOrder" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                      ignoreArrayOrder
                    </label>
                    <label class="flex items-center gap-1.5 text-xs cursor-pointer" :class="t.mutedText">
                      <input v-model="bp.ignoreExtraElements" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                      ignoreExtraElements
                    </label>
                  </div>
                </div>
              </div>
              <p v-if="bodyPatterns.length === 0" :class="t.dimText" class="text-xs italic">
                No body pattern filters
              </p>
            </div>

            <!-- Action row — every input on this panel auto-fires an
                 HTTP request to WireMock (debounced for free-text
                 fields). The Search button is gone; this row holds
                 only Reset + an in-flight indicator + any validation
                 error. The "Stub Metadata" filter that used to live
                 here was client-side only and has been removed in
                 favour of fully-server-side matching. -->
            <div class="flex flex-wrap items-center gap-3 pt-3 border-t" :class="t.border">
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
              <span
                v-if="isSearching || isLoading"
                :class="t.emeraldText"
                class="inline-flex items-center gap-2 text-xs font-medium"
              >
                <ArrowPathIcon class="w-3.5 h-3.5 animate-spin" />
                Querying WireMock…
              </span>
              <span v-if="searchError" class="text-xs text-red-500 ml-2">{{ searchError }}</span>
            </div>
          </div>
        </div>

        <div :class="t.mutedText" class="text-sm">
          Showing {{ totalRequests }} request{{ totalRequests === 1 ? '' : 's' }} (server-filtered)
        </div>
      </div>

      <!-- Loading State -->
      <div v-if="isLoading" class="flex justify-center items-center py-12">
        <BaseSpinner />
      </div>

      <!-- Empty State -->
      <div v-else-if="totalRequests === 0" :class="t.card" class="border rounded-lg p-12 text-center">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <h3 :class="t.primaryText" class="mt-4 text-lg font-medium">
          {{ isAnyInputSet ? 'No requests match your filters' : 'No requests captured' }}
        </h3>
        <p :class="t.mutedText" class="mt-2">
          {{ isAnyInputSet ? 'Try loosening your filters or pressing Reset' : 'Make some API requests to WireMock to see them here' }}
        </p>
      </div>

      <!-- Requests List -->
      <div v-else class="space-y-4">
        <div
          v-for="request in requests"
          :key="request.id"
          :class="isDark ? 'bg-gray-800 border-gray-700 hover:bg-gray-900' : 'bg-white border-gray-200 hover:bg-gray-100'"
          class="border rounded-lg p-6 hover:shadow-lg transition-all duration-300 ease-in-out cursor-pointer"
          @click="viewRequest(request)"
        >
          <!-- Request Header -->
          <div class="mb-4">
            <div class="flex items-center gap-2 mb-2">
              <span :class="getMethodBgColor(request.request.method)" class="px-2 py-1 rounded text-xs font-bold text-white">
                {{ request.request.method || 'ANY' }}
              </span>
              <span v-if="request.responseDefinition" :class="getStatusCodeColor(request.responseDefinition.status)" class="px-2 py-1 rounded text-xs font-bold text-white">
                {{ request.responseDefinition.status }}
              </span>
            </div>
            <p :class="t.mutedText" class="text-sm font-mono truncate break-all">
              {{ request.request.url }}
            </p>
          </div>

          <!-- Request Details -->
          <div :class="isDark ? 'bg-gray-900' : 'bg-gray-100'" class="rounded p-3">
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
              <div>
                <p :class="t.subtleText" class="uppercase tracking-wide font-medium">Client IP</p>
                <p :class="t.label" class="font-mono mt-1">{{ request.request.clientIp }}</p>
              </div>
              <div>
                <p :class="t.subtleText" class="uppercase tracking-wide font-medium">Scheme</p>
                <p :class="t.label" class="font-mono mt-1">{{ request.request.scheme }}</p>
              </div>
              <div>
                <p :class="t.subtleText" class="uppercase tracking-wide font-medium">Host</p>
                <p :class="t.label" class="font-mono mt-1">{{ request.request.host }}</p>
              </div>
              <div>
                <p :class="t.subtleText" class="uppercase tracking-wide font-medium">Port</p>
                <p :class="t.label" class="font-mono mt-1">{{ request.request.port }}</p>
              </div>
              <div class="sm:col-span-2">
                <p :class="t.subtleText" class="uppercase tracking-wide font-medium">Logged</p>
                <p :class="t.label" class="mt-1">{{ formatDate(request.request.loggedDate) }}</p>
              </div>
            </div>
          </div>

          <!--
            Action bar — labeled icon buttons styled like the mock-card
            action row in ProjectDetailView (icon + text, not bare icons).
            All use @click.stop so the wrapping card-click (viewRequest)
            doesn't fire alongside them. Each button is conditional:
              · Project / Edit WireMate / Stub render only when the request
                matched a WireMate-managed stub (metadata.projectId / id).
              · Delete renders only when the row carries a real WireMock id
                (the synthetic `find-…` ids can't be deleted server-side).
            The whole bar is hidden when none of the above apply so an
            empty bordered strip never renders.
          -->
          <div
            v-if="hasRowActions(request)"
            class="flex items-center flex-wrap gap-2 mt-4 pt-4 border-t"
            :class="t.border"
          >
            <RouterLink
              v-if="getMatchedProjectId(request)"
              :to="{ name: 'project', params: { id: getMatchedProjectId(request)! } }"
              @click.stop
              class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-sky-400 hover:bg-sky-500/10' : 'text-sky-600 hover:bg-sky-50'"
              title="Open the WireMate project this stub belongs to"
            >
              <FolderIcon class="w-4 h-4" />
              Project
            </RouterLink>
            <button
              v-if="getEditableMockParams(request)"
              type="button"
              @click.stop="editMockFromRequest(request)"
              class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-emerald-400 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'"
              title="Open this stub in the WireMate mock editor"
            >
              <WireMateLogo :size="16" />
              Edit WireMate
            </button>
            <button
              v-if="getMatchedStubId(request)"
              type="button"
              @click.stop="viewMatchedStub(request)"
              class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-indigo-400 hover:bg-indigo-500/10' : 'text-indigo-600 hover:bg-indigo-50'"
              title="Open the raw WireMock stub"
            >
              <CubeIcon class="w-4 h-4" />
              Stub
            </button>
            <button
              v-if="request.id && !request.id.startsWith('find-')"
              type="button"
              @click.stop="openDeleteRequestModal(request)"
              :disabled="deletingIds.has(request.id)"
              :aria-label="`Delete request ${request.request.url}`"
              class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
              title="Delete this request"
            >
              <TrashIcon class="w-4 h-4" />
              Delete
            </button>
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
    <BaseConfirmModal
      v-if="showDeleteAllModal"
      :title="`Delete all ${totalRequests} request${totalRequests === 1 ? '' : 's'}?`"
      :message="`This will clear WireMock's request journal entirely. This action cannot be undone.`"
      confirm-text="Yes, delete all"
      cancel-text="Cancel"
      submitting-text="Deleting..."
      variant="danger"
      :submitting="isDeletingAll"
      @confirm="confirmDeleteAll"
      @cancel="closeDeleteAllModal"
    />

    <!-- Per-row delete confirmation. Mirrors the Delete-All modal pattern but
         scoped to a single LoggedRequest. The trash icon on each card now
         opens this modal instead of firing DELETE /__admin/requests/{id}
         directly, so the user always gets an explicit confirmation step. -->
    <BaseConfirmModal
      v-if="requestToDelete"
      :title="`Delete this request?`"
      :message="deleteRequestMessage"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      submitting-text="Deleting..."
      variant="danger"
      :submitting="isDeletingSingle"
      @confirm="confirmDeleteRequest"
      @cancel="closeDeleteRequestModal"
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
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { BaseConfirmModal, BaseSpinner, BaseToast, BaseToastEnum, methodBadgeSolid, statusBadgeSolid, useDebouncedRef, useTheme, useThemeClasses, useToast } from 'mgv-backoffice'
import { findRequests, getAllRequests, deleteAllRequests, deleteRequest } from '../services/requestJournalService'
// Note: removeRequestsByPattern was previously imported here for the
// "Remove matching" toolbar button, which has been removed in favour of
// the per-row delete + Delete All affordances.
import RequestDetailModal from '../components/RequestDetailModal.vue'
import type { LoggedRequest, RequestPattern } from '../types/requestJournal'
import {
  ArrowPathIcon,
  TrashIcon,
  FolderIcon,
  CubeIcon,
  DocumentTextIcon,
} from '@heroicons/vue/24/outline'
import WireMateLogo from '../components/WireMateLogo.vue'
import PageHeader from '../components/PageHeader.vue'

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
const t = useThemeClasses()
const route = useRoute()
const router = useRouter()

// ── State ──────────────────────────────────────────────────────────────
//
// The Logs page is now fully server-side: every input on the search
// panel auto-fires an HTTP request to WireMock (POST
// /__admin/requests/find when a pattern is built, or
// GET /__admin/requests when only `since` is set). No client-side
// trimming runs over the loaded list — what's on screen is what
// WireMock returned. Free-text inputs are debounced (~400 ms) so we
// don't spam the admin port while the user is typing.
//
// `contains` is still exposed as a URL match type for ergonomic
// substring search, but it's translated to `urlPattern: ".*<escaped>.*"`
// before being sent to /find so the matching happens on WireMock too.

const requests = ref<LoggedRequest[]>([])
const isLoading = ref(false)
const isSearching = ref(false)
const selectedRequest = ref<LoggedRequest | null>(null)
const showGraphs = ref(true)
const { showToast, toastMessage, toastType, showToastMessage } = useToast()

// URL search — single input; the match type names the WireMock matcher
// the value will be sent as. `contains` is a UX shorthand for an
// auto-escaped urlPattern (see buildPattern).
type UrlMatchType = 'contains' | 'url' | 'urlPath' | 'urlPattern' | 'urlPathPattern'
function parseUrlMatchType(raw: unknown): UrlMatchType {
  if (raw === 'url' || raw === 'urlPath' || raw === 'urlPattern' || raw === 'urlPathPattern') {
    return raw
  }
  return 'contains'
}
const urlMatchType = ref<UrlMatchType>(parseUrlMatchType(route.query.urlMatch))
const urlValue = ref(typeof route.query.search === 'string' ? route.query.search : '')

// Method goes server-side on the /find pattern. Since maps to the
// `?since=ISO` query param of GET /__admin/requests when no /find
// pattern is in play.
const filterMethod = ref(typeof route.query.method === 'string' ? route.query.method : '')
const sinceFilter = ref('')

// Regex-escape a literal string so it can be embedded inside a
// `urlPattern` regex without surprising the user (e.g. dots in
// "/api/v1.0" should match literally, not "any char"). Used only by
// the `contains` URL match type.
function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// Server-side-only pattern inputs
// Stable per-row id so Vue's v-for can track rows across `splice()` deletions
// without reusing DOM nodes by position (which would shift form values into
// the wrong cells when a row in the middle is removed).
interface HeaderFilter {
  id: string
  key: string
  matchType: 'equalTo' | 'contains' | 'matches'
  value: string
}
interface BodyPatternFilter {
  id: string
  type: 'equalToJson' | 'matchesJsonPath' | 'contains' | 'matches' | 'equalTo'
  value: string
  ignoreArrayOrder: boolean
  ignoreExtraElements: boolean
}

function newRowId(): string {
  // `crypto.randomUUID` is available in all evergreen browsers and Node 19+
  // (which Vite's dev server runs). Falls back to a counter-based id when
  // the API isn't available (e.g. very old browsers).
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `row-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
const headers = ref<HeaderFilter[]>([])
// Query parameters share HeaderFilter's shape (name + matcher + value)
// because WireMock's RequestPattern accepts the same matcher object for
// both. Reusing the type lets us reuse headerKeyInvalid/headerValueInvalid
// /headerRowError helpers for validation messages.
const queryParamFilters = ref<HeaderFilter[]>([])
const bodyPatterns = ref<BodyPatternFilter[]>([])
const searchError = ref('')

// ── Computed ───────────────────────────────────────────────────────────

const totalRequests = computed(() => requests.value.length)

// True when the user has filled in anything at all — controls the Reset
// button's enabled state and the empty-state copy.
const isAnyInputSet = computed(() => {
  return urlValue.value.trim() !== ''
    || filterMethod.value !== ''
    || sinceFilter.value !== ''
    || headers.value.some(h => h.key || h.value)
    || queryParamFilters.value.some(q => q.key || q.value)
    || bodyPatterns.value.some(bp => bp.value.trim())
})

// Placeholders mirror the WireMock /requests/find docs so the form
// reads as a one-to-one analogue of the equivalent curl call:
//   url             → /api/orders?status=open  (exact path + query)
//   urlPath         → /api/orders              (path only, ignore query)
//   urlPattern      → /api/orders.*            (regex on full URL)
//   urlPathPattern  → /api/orders/[0-9]+       (regex on path only)
const urlPlaceholder = computed(() => {
  switch (urlMatchType.value) {
    case 'url': return '/api/orders?status=open'
    case 'urlPath': return '/api/orders'
    case 'urlPattern': return '/api/orders.*'
    case 'urlPathPattern': return '/api/orders/[0-9]+'
    case 'contains':
    default:
      return 'Search by URL…'
  }
})

// Matched / unmatched counts are derived purely from the loaded list.
// Entries where `wasMatched` is absent (e.g. the flat
// LoggedRequestDetail shape returned by /find on some WireMock
// builds) are treated as unknown, so the tile shows "—" instead of
// a misleading zero.
function countByMatch(list: LoggedRequest[], matched: boolean): number | null {
  const withInfo = list.filter(r => r.wasMatched !== undefined)
  if (withInfo.length === 0) return null
  return withInfo.filter(r => r.wasMatched === matched).length
}

const matchedCount = computed<number | null>(() => countByMatch(requests.value, true))
const unmatchedCount = computed<number | null>(() => countByMatch(requests.value, false))

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
  for (const req of requests.value) {
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
  for (const req of requests.value) {
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
  const sorted = [...requests.value]
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
//
// Honours the `since` filter server-side: when set, it's translated to
// the `?since=<ISO>` query param on GET /__admin/requests so WireMock
// trims the journal before the response, instead of us pulling
// everything and filtering on the client.
async function loadRequests() {
  isLoading.value = true
  try {
    // Use GET /__admin/requests for the unfiltered load: it returns
    // wrapped ServeEvents with the real WireMock-assigned id, which is
    // what the per-row Delete button needs. Going through
    // findRequests({}) was returning flat LoggedRequestDetail entries on
    // this WireMock build - those have no id, so the trash icon was
    // hidden on every card.
    const response = await getAllRequests({ since: sinceIsoForServer.value ?? undefined })
    requests.value = response.requests
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Failed to load requests'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isLoading.value = false
  }
}

// `sinceFilter` arrives as a `datetime-local` string (no timezone). We
// parse it through the local timezone (matching what the user picked in
// the picker) and re-emit as ISO-8601 UTC for the WireMock query param.
// Returns null when the input is empty or unparseable so the call
// degrades cleanly to "no since".
const sinceIsoForServer = computed<string | null>(() => {
  if (!sinceFilter.value) return null
  const t = new Date(sinceFilter.value).getTime()
  if (Number.isNaN(t)) return null
  return new Date(t).toISOString()
})

// ── Header / Body Pattern helpers ──────────────────────────────────────

function addHeaderFilter() {
  headers.value.push({ id: newRowId(), key: '', matchType: 'equalTo', value: '' })
}

function addQueryParamFilter() {
  queryParamFilters.value.push({ id: newRowId(), key: '', matchType: 'equalTo', value: '' })
}

function addBodyPattern() {
  bodyPatterns.value.push({ id: newRowId(), type: 'equalToJson', value: '', ignoreArrayOrder: true, ignoreExtraElements: true })
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
  if (queryParamFilters.value.some(q => headerRowError(q))) return true
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

  // URL matchers map 1:1 to WireMock RequestPattern fields. The
  // `contains` UX shorthand becomes a regex-escaped urlPattern so the
  // substring search runs server-side too — that's what lets us drop
  // every client-side filter while preserving the "type and see
  // matches" feel.
  if (urlValue.value.trim()) {
    const v = urlValue.value.trim()
    switch (urlMatchType.value) {
      case 'url': pattern.url = v; break
      case 'urlPath': pattern.urlPath = v; break
      case 'urlPattern': pattern.urlPattern = v; break
      case 'urlPathPattern': pattern.urlPathPattern = v; break
      case 'contains': pattern.urlPattern = `.*${escapeRegex(v)}.*`; break
    }
  }

  const validHeaders = headers.value.filter(h => h.key && h.value)
  if (validHeaders.length > 0) {
    pattern.headers = {}
    for (const h of validHeaders) {
      pattern.headers[h.key] = { [h.matchType]: h.value }
    }
  }

  const validQueryParams = queryParamFilters.value.filter(q => q.key && q.value)
  if (validQueryParams.length > 0) {
    pattern.queryParameters = {}
    for (const q of validQueryParams) {
      pattern.queryParameters[q.key] = { [q.matchType]: q.value }
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

// Auto-fire search — no longer triggered by a button click but by every
// reactive input change (debounced for free-text via the watcher
// further down). Skips the network call when validation rows have an
// error so a half-typed header doesn't spam the admin port; the
// validation message stays visible until the row is fixed.
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
    // `since` doesn't fit on the /find pattern (WireMock's /find body
    // doesn't accept a date matcher), but GET /__admin/requests does
    // accept it as a query param. So we narrow the unfiltered fetch
    // server-side and use /find when there's a richer pattern.
    const response = hasServerPattern
      ? await findRequests(pattern)
      : await getAllRequests({ since: sinceIsoForServer.value ?? undefined })
    requests.value = response.requests
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
  queryParamFilters.value = []
  bodyPatterns.value = []
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

// Project shortcut on the log card mirrors the /stubs page Project
// icon: pull `metadata.projectId` from the matched stub when present,
// returning null for unmatched requests or stubs added through the
// raw WireMock API without WireMate's tagging.
function getMatchedProjectId(req: LoggedRequest): string | null {
  const pid = req.stubMapping?.metadata?.projectId
  return typeof pid === 'string' && pid ? pid : null
}

// Raw WireMock stub id — used by the "Stub" (CubeIcon) shortcut to
// route into /stubs/:id, mirroring the Stub button on mock cards in
// ProjectDetailView. Returns null when the request didn't match any
// stub (or for the legacy flat shape that doesn't expose stubMapping).
function getMatchedStubId(req: LoggedRequest): string | null {
  const id = req.stubMapping?.id
  return typeof id === 'string' && id ? id : null
}

function viewMatchedStub(req: LoggedRequest) {
  const id = getMatchedStubId(req)
  if (!id) return
  router.push({ name: 'stub-detail', params: { id } })
}

// True when the request row has at least one action to show, so the
// bottom action bar (and its top border) only renders when populated.
function hasRowActions(req: LoggedRequest): boolean {
  return !!getMatchedProjectId(req)
    || !!getEditableMockParams(req)
    || !!getMatchedStubId(req)
    || !!(req.id && !req.id.startsWith('find-'))
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
  const desiredUrlMatch = (newType && newType !== 'contains') ? newType : ''
  if (
    currentSearch === (newUrl || '') &&
    currentUrlMatch === desiredUrlMatch &&
    currentMethod === (newMethod || '')
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

const debouncedUrlValue = useDebouncedRef(urlValue, 400)
const debouncedHeadersFingerprint = useDebouncedRef(
  computed(() => JSON.stringify(headers.value)),
  400,
)
const debouncedQueryParamsFingerprint = useDebouncedRef(
  computed(() => JSON.stringify(queryParamFilters.value)),
  400,
)
const debouncedBodyPatternsFingerprint = useDebouncedRef(
  computed(() => JSON.stringify(bodyPatterns.value)),
  400,
)

let initialFireSkipped = false
function autoFire() {
  // Skip the very first synchronous fire - onMounted does the initial
  // fetch already; we don't want a double-call on page load.
  if (!initialFireSkipped) {
    initialFireSkipped = true
    return
  }
  void executeSearch()
}

watch(
  [
    debouncedUrlValue,
    urlMatchType,
    filterMethod,
    sinceFilter,
    debouncedHeadersFingerprint,
    debouncedQueryParamsFingerprint,
    debouncedBodyPatternsFingerprint,
  ],
  () => autoFire(),
)

onMounted(() => {
  // Route in with `?search=...` (or any other filter query param) → fire a
  // pattern-based search so WireMock returns *only* matching requests via
  // POST /__admin/requests/find. Without this, a URL search seeded from
  // the route would land the user on an unfiltered journal, which
  // contradicts the deep-link intent. The watcher above debounces a
  // second pass once filters stabilise.
  // The auto-fire watcher covers any subsequent filter mutation; we
  // just need to seed the initial list here.
  void loadRequests()
})
</script>
