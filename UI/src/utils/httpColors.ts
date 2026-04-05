/**
 * HTTP method / status code → Tailwind class helpers.
 *
 * Two visual families are used in the UI:
 *
 *   Solid family (600 weight, white text): used in the request journal and
 *   request-detail modal. Bold, saturated badges for inline rows.
 *
 *   Bright/tinted family (500 weight + tinted backgrounds): used on stub
 *   cards where the badge sits against a softer card background and must
 *   also adapt between dark and light themes.
 *
 * Centralising these keeps the mapping consistent across views and prevents
 * the tables from drifting apart over time (which they already had).
 */

type MethodColorMap = Readonly<Record<string, string>>

const SOLID_METHOD_COLORS: MethodColorMap = {
  GET: 'bg-blue-600',
  POST: 'bg-emerald-600',
  PUT: 'bg-amber-600',
  DELETE: 'bg-red-600',
  PATCH: 'bg-purple-600',
  HEAD: 'bg-gray-600',
}

const BRIGHT_METHOD_COLORS: MethodColorMap = {
  GET: 'bg-blue-500',
  POST: 'bg-green-500',
  PUT: 'bg-yellow-500',
  DELETE: 'bg-red-500',
  PATCH: 'bg-purple-500',
  HEAD: 'bg-gray-500',
}

/** Solid 600-weight method badge (request journal / detail modal). */
export function methodBadgeSolid(method?: string): string {
  if (!method) return 'bg-gray-600'
  return SOLID_METHOD_COLORS[method.toUpperCase()] ?? 'bg-gray-600'
}

/** Bright 500-weight method badge (stub cards). */
export function methodBadgeBright(method?: string): string {
  if (!method) return 'bg-gray-500'
  return BRIGHT_METHOD_COLORS[method.toUpperCase()] ?? 'bg-gray-500'
}

/** Solid 600-weight status badge with white text (journal / detail modal). */
export function statusBadgeSolid(status?: number): string {
  const code = status ?? 0
  if (code >= 200 && code < 300) return 'bg-emerald-600'
  if (code >= 300 && code < 400) return 'bg-blue-600'
  if (code >= 400 && code < 500) return 'bg-amber-600'
  if (code >= 500) return 'bg-red-600'
  return 'bg-gray-600'
}

/** Dark/light-aware tinted status badge (stub cards). */
export function statusBadgeTinted(status: number | undefined, isDark: boolean): string {
  const code = status ?? 200
  if (isDark) {
    if (code >= 200 && code < 300) return 'bg-green-500/15 text-green-400'
    if (code >= 300 && code < 400) return 'bg-blue-500/15 text-blue-400'
    if (code >= 400 && code < 500) return 'bg-yellow-500/15 text-yellow-400'
    return 'bg-red-500/15 text-red-400'
  }
  if (code >= 200 && code < 300) return 'bg-green-100 text-green-800'
  if (code >= 300 && code < 400) return 'bg-blue-100 text-blue-800'
  if (code >= 400 && code < 500) return 'bg-yellow-100 text-yellow-800'
  return 'bg-red-100 text-red-800'
}
