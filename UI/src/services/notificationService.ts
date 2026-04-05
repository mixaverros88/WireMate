import type { Notification, NotificationPage } from '../types/notification'
import { API_BASE_URL } from '../config'

const BASE_URL = `${API_BASE_URL}/backoffice`

/** Default page size — keeps the payload bounded so the renderer never hangs on large datasets. */
export const DEFAULT_PAGE_SIZE = 50
/** Client-side timeout — if the backend can't return within this window we surface an actionable error. */
const REQUEST_TIMEOUT_MS = 15_000

export interface FetchNotificationsParams {
  limit?: number
  offset?: number
  signal?: AbortSignal
}

/**
 * Fetch a single page of notifications. Uses an internal AbortController so a stuck
 * backend surfaces a timeout error instead of an indefinite spinner.
 */
export async function fetchNotificationsPage(
  { limit = DEFAULT_PAGE_SIZE, offset = 0, signal }: FetchNotificationsParams = {},
): Promise<NotificationPage> {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

  // Chain the caller's signal (if any) into our controller so either can abort.
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', () => controller.abort(), { once: true })
  }

  try {
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) })
    const response = await fetch(`${BASE_URL}/notifications?${params.toString()}`, {
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new Error(`Failed to fetch notifications (HTTP ${response.status})`)
    }

    // The backend has gone through several response shapes over time and
    // we normalize to NotificationPage here so the view never sees an
    // undefined `items` array (which would break `notifications.length` in
    // the template and leave the page stuck on its initial loading state).
    //
    // Supported shapes:
    //   - Bare array: Notification[]              (legacy pre-pagination)
    //   - NotificationPage: { items, total, limit, offset }
    //   - Spring Data Page: { content, totalElements, size, number }
    const data = (await response.json()) as unknown
    return normalizeNotificationResponse(data, limit, offset)
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new Error(
        `Notifications request timed out after ${REQUEST_TIMEOUT_MS / 1000}s. The server may be overloaded.`,
      )
    }
    throw err
  } finally {
    window.clearTimeout(timeoutId)
  }
}

/** Back-compat shim for any legacy callers that expect a bare list. */
export async function fetchNotifications(): Promise<Notification[]> {
  const page = await fetchNotificationsPage()
  return page.items
}

/**
 * Accepts several historically-used backend response shapes and returns a
 * stable `NotificationPage`. Exported for unit testing only.
 *
 * If the shape is completely unrecognized, returns a well-formed empty page
 * rather than throwing — the view can then render "No notifications"
 * instead of getting stuck on the loading state because `items` is
 * undefined.
 */
export function normalizeNotificationResponse(
  data: unknown,
  fallbackLimit: number,
  fallbackOffset: number,
): NotificationPage {
  // 1) Bare array (legacy)
  if (Array.isArray(data)) {
    return {
      items: data as Notification[],
      total: data.length,
      limit: fallbackLimit,
      offset: fallbackOffset,
    }
  }

  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>

    // 2) NotificationPage shape: { items, total, limit, offset }
    if (Array.isArray(obj.items)) {
      return {
        items: obj.items as Notification[],
        total: typeof obj.total === 'number' ? obj.total : (obj.items as Notification[]).length,
        limit: typeof obj.limit === 'number' && obj.limit > 0 ? obj.limit : fallbackLimit,
        offset: typeof obj.offset === 'number' ? obj.offset : fallbackOffset,
      }
    }

    // 3) Spring Data Page shape: { content, totalElements, size, number }
    if (Array.isArray(obj.content)) {
      const size = typeof obj.size === 'number' && obj.size > 0 ? obj.size : fallbackLimit
      const page = typeof obj.number === 'number' ? obj.number : 0
      return {
        items: obj.content as Notification[],
        total:
          typeof obj.totalElements === 'number'
            ? obj.totalElements
            : (obj.content as Notification[]).length,
        limit: size,
        offset: page * size,
      }
    }
  }

  // Unknown shape — fail soft so the UI can render its empty state rather
  // than stall on "Loading…" forever.
  return { items: [], total: 0, limit: fallbackLimit, offset: fallbackOffset }
}
