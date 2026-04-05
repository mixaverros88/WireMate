/**
 * HTML sanitizer for notification bodies.
 *
 * `notification.name` arrives as rich HTML from the backend (b/a/em/
 * code/pre/ul/li/p/br). Even though the source is internal, we belt-
 * and-suspenders sanitise via an allow-list before binding into
 * `v-html` so a future change on the backend can't accidentally
 * smuggle script tags, inline event handlers, or `javascript:` href
 * values into the page.
 *
 * Implementation:
 *   1. Parse with `DOMParser` into an off-document tree.
 *   2. Walk the tree once, replacing any disallowed element with its
 *      `textContent` (the readable text survives, the wrapper is gone).
 *   3. On every element, strip every attribute except the per-tag
 *      allow-list below.
 *   4. Re-validate `a[href]` against an explicit scheme whitelist —
 *      `javascript:`, `data:`, `vbscript:`, and `file:` are rejected.
 *   5. Force `rel="noopener noreferrer" target="_blank"` on every
 *      surviving anchor so external links can't reach back through
 *      `window.opener`.
 *
 * Returns the cleaned HTML string, ready for `v-html`.
 *
 * Was originally inlined in `views/NotificationsView.vue`. Lifted into
 * `src/utils/` so it can be unit-tested in isolation (and so future
 * callers can reuse the same allow-list).
 */

const ALLOWED_TAGS: ReadonlySet<string> = new Set([
  'A', 'B', 'STRONG', 'I', 'EM', 'CODE', 'PRE', 'BR',
  'P', 'UL', 'OL', 'LI', 'SPAN', 'DIV',
])

/**
 * Tags whose contents are also dropped (not unwrapped as text). For
 * most disallowed elements we keep the inner text so the user-visible
 * copy survives even when the wrapper is stripped, but for
 * code-bearing elements like `<script>` and `<style>` the inner text
 * is the payload itself — leaving it as a visible text node would
 * splat raw JS / CSS source into the notification card. Drop the
 * element entirely instead.
 */
const STRIP_WITH_CONTENT: ReadonlySet<string> = new Set([
  'SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'NOSCRIPT', 'TEMPLATE',
])

const ALLOWED_ATTRS_BY_TAG: Readonly<Record<string, ReadonlySet<string>>> = {
  A: new Set(['href', 'title']),
}

/**
 * Returns true when the supplied href is safe to render as-is. Blocks
 * every scheme that has historically been a vector for XSS or local-
 * file leakage.
 */
export function isSafeHref(value: string): boolean {
  const trimmed = value.trim().toLowerCase()
  return (
    trimmed.startsWith('http://')
    || trimmed.startsWith('https://')
    || trimmed.startsWith('mailto:')
    || trimmed.startsWith('tel:')
    || trimmed.startsWith('/')
    || trimmed.startsWith('#')
    || trimmed === ''
  )
}

function sanitizeNode(node: Node): void {
  if (node.nodeType !== Node.ELEMENT_NODE) return
  const el = node as Element
  const tag = el.tagName.toUpperCase()
  // Disallowed element: replace with its textContent so the text the
  // user is meant to read survives, but the wrapper is gone. For
  // code-bearing elements (`<script>`, `<style>`, etc.) we drop the
  // contents too so the JS/CSS source itself doesn't render as
  // visible text.
  if (!ALLOWED_TAGS.has(tag)) {
    if (STRIP_WITH_CONTENT.has(tag)) {
      el.remove()
    } else {
      el.replaceWith(document.createTextNode(el.textContent ?? ''))
    }
    return
  }
  // Strip every attribute that isn't in the per-tag allow-list, and
  // re-validate `a[href]` against `isSafeHref`.
  const allowedAttrs = ALLOWED_ATTRS_BY_TAG[tag] ?? new Set<string>()
  for (const attr of Array.from(el.attributes)) {
    if (!allowedAttrs.has(attr.name.toLowerCase())) {
      el.removeAttribute(attr.name)
      continue
    }
    if (tag === 'A' && attr.name.toLowerCase() === 'href' && !isSafeHref(attr.value)) {
      el.removeAttribute(attr.name)
    }
  }
  // External-link hardening: every surviving anchor opens safely.
  if (tag === 'A' && el.hasAttribute('href')) {
    el.setAttribute('rel', 'noopener noreferrer')
    el.setAttribute('target', '_blank')
  }
  // Recurse over children — copy the live list first so removals don't
  // skip siblings.
  for (const child of Array.from(el.childNodes)) {
    sanitizeNode(child)
  }
}

export function sanitizeNotificationHtml(raw: string | undefined | null): string {
  if (!raw) return ''
  const doc = new DOMParser().parseFromString(`<div>${raw}</div>`, 'text/html')
  const root = doc.body.firstElementChild
  if (!root) return ''
  for (const child of Array.from(root.childNodes)) {
    sanitizeNode(child)
  }
  return root.innerHTML
}
