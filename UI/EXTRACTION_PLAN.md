# mgv-backoffice extraction plan

Audit of `C:\Users\mixal\Desktop\archive\projects\WireMate\UI` against the current
`mgv-backoffice` lib (`C:\Users\mixal\Desktop\archive\projects\ui-lib`).

The goal: identify every WireMate-side file that is actually generic backoffice
plumbing and could move into `mgv-backoffice` for reuse by other backoffices.
Nothing here changes code — it's a shortlist with the work each move needs.

---

## What `mgv-backoffice` already ships

Components: `BaseAlert`, `BaseBadge`, `BaseBreadcrumb`, `BaseButton`, `BaseLine`,
`BaseLogo`, `BaseModal`, `BaseRow`, `BaseSpinner`, `BaseToast`, `ColoredSquares`,
`EarningsCard`, `EuroAmount`, `Pagination`, `TrendArrow`.

Enums: `AlertEnum`, `BaseBadgeEnum`, `BaseButtonEnum`, `BaseButtonSizeEnum`,
`BaseLogoEnum`/`BaseLoginEnum`, `BaseModalEnum`, `BaseToastEnum`, `ColorsEnums`,
`LineEnum`, `PositioningEnum`.

Utils: `getBaseColor`, `getBaseColorOf`.

Notable gap: **no dark-mode primitives**, **no layout/sidebar**, **no modal
shell**, **no collapsible**, **no theme composables**. WireMate has all of these
and they're the highest-leverage extractions.

---

## Tier 1 — extract as-is or with trivial generalization

These have zero WireMate domain code (or one line of branding) and would be a
direct win for any future backoffice.

### `composables/useTheme.ts` → `mgv-backoffice/useTheme`

Singleton dark/light ref with localStorage persistence and `<html class="dark">`
sync. **One change**: replace the hardcoded `wiremate-theme` localStorage key
with a parameter (e.g. `useTheme({ storageKey: 'wiremate-theme' })`) or a
generic default like `mgv-theme`. Everything else is portable.

### `composables/useThemeClasses.ts` → `mgv-backoffice/useThemeClasses`

This is effectively the **design token sheet** for the dark/light system —
named roles (`card`, `cardAlt`, `pageBg`, `border`, `primaryText`, `bodyText`,
`label`, `mutedText`, `dimText`, `input`, `ghostButton`, `emeraldText`,
`amberText`, `redText`, …) returned as computed class strings. The single most
valuable thing to extract; everything else in Tier 1 depends on it.

Move as-is. No WireMate-specific roles.

### `composables/useEscapeKey.ts` → `mgv-backoffice/useEscapeKey`

Six lines. Pure utility. Move as-is.

### `composables/useDebounce.ts` (`useDebouncedRef`) → `mgv-backoffice/useDebouncedRef`

Pure utility, scope-disposal aware. Move as-is.

### `composables/useToast.ts` → `mgv-backoffice/useToast`

Per-component toast controller (state refs + auto-hide timer cleared on
unmount). Already imports `BaseToastEnum` from `mgv-backoffice`, so it logically
*belongs* in the lib. Move as-is.

### `composables/useMobileSidebar.ts` → `mgv-backoffice/useMobileSidebar`

Module-scoped singleton ref for the off-canvas state. Pairs with the sidebar
extraction below. Move as-is.

### `utils/httpColors.ts` → `mgv-backoffice/httpColors`

`methodBadgeSolid`, `methodBadgeBright`, `statusBadgeSolid`, `statusBadgeTinted`.
Generic API-tooling helpers; reusable by any backoffice that displays HTTP
verbs or status codes. Move as-is.

### `components/ModalShell.vue` → `BaseModalShell` (or `BaseDialog`)

Already a clean abstraction: `Teleport` to body, backdrop, themed card, escape
key, aria-modal. Slots: `icon`, default (body), `footer`. Props: `title`,
`maxWidthClass`, `manualClose`. No domain references at all.

**Note on overlap with the existing `BaseModal`**: today's `BaseModal` is a
fixed-shape confirm dialog with built-in delete/success modes. `ModalShell` is
strictly more flexible and is the building block all WireMate modals already
use. Recommendation: ship `BaseModalShell` as a *new* export, and let the
existing `BaseModal` keep its current contract for backward compatibility.

### `components/CollapsibleSection.vue` → `BaseCollapsibleSection`

Pure presentation; parent owns `collapsed`. Slot for body, props `title`,
`collapsed`, optional `badge`. Uses `useThemeClasses()` (Tier 1). One thing to
review while moving: the body's `bg-white`-in-both-modes choice is a deliberate
WireMate decision — for the lib, prefer following the theme (white in light,
gray-800 in dark) and let consumers override via a `bodyClass` prop if they
need the WireMate behavior.

### `components/ConfirmModal.vue` → `BaseConfirmModal`

Built on `ModalShell`. Props: `title`, `message`, `confirmText`, `cancelText`,
`submittingText`, `variant: 'danger' | 'warning'`, `submitting`. Already
domain-free. Move once `BaseModalShell` lands.

### `components/CloneModal.vue` → `BaseTextInputModal` (rename)

Despite the name, the file is a generic "ask the user for a single string and
confirm" dialog with nice UX (preserves typed input on backdrop click, focuses
the input on mount). Drop the "clone" framing and the `DocumentDuplicateIcon`
default — accept the icon as a slot, the verb as `confirmText`, and ship as
`BaseTextInputModal`.

### `views/NotFoundView.vue` → `BaseNotFoundPage`

Just an icon + 404 + message + "go home" button. Generalize the home target:
take `homeRouteName` (default `'home'`) and `homeLabel` (default `'Go home'`)
as props. Drop the emerald hardcode in favor of `BaseButton` with a color
variant.

---

## Tier 2 — extract with meaningful generalization

Useful patterns, but they currently bake in too much WireMate-specific stuff
to lift straight across. Worth doing but plan for the API.

### `components/AppSidebar.vue` → `BaseSidebar`

This is the big-ticket item: responsive backoffice shell, fixed on desktop,
slide-in on mobile, focus management on open/close, theme toggle, accent
highlights, footer area. The skeleton is exactly what every other backoffice
needs. What it currently bakes in that has to become configurable:

| Currently hardcoded | Should become |
|---|---|
| `WireMateLogo` component | `logo` slot (or `logoComponent` prop) |
| `__APP_VERSION__` define | `version` prop + `appName` prop |
| GitHub link to `mixaverros88/WireMate` | `footer` slot, or `links` prop |
| WireMock health check (`fetchHealth` from settingsService) | remove from base; expose a `status` slot in the footer for consumers to drop in their own indicator |
| `navSections` array (hardcoded route names) | `sections: NavSection[]` prop |
| Emerald accent throughout | `accentColor` prop tied to existing `BaseButtonEnum`/`ColorsEnums`, or read from a CSS var |
| `goHome` → `router.push({ name: 'projects' })` | `homeRouteName` prop (default `'home'`) |
| Theme toggle | keep, but allow `:showThemeToggle="false"` |

Once it lives in the lib, the WireMate-side `AppSidebar` shrinks to a wrapper
that passes WireMate's nav config + the health indicator into a `status` slot.

### `components/SelectProjectModal.vue` → `BaseEntityPickerModal`

The shape (search input + scrollable selectable list + confirm) is a generic
"pick one from a list" modal. Currently fetches `/api/projects` directly via
`fetchProjects`. To extract: take `items: { id, label }[]` (or a `loader:
() => Promise<Item[]>`), `searchPlaceholder`, `title`, `message`,
`confirmText`, `submittingText`, and emit `confirm(id)`. Whether to ship this
depends on whether the next backoffice will need a "pick one" picker; the
pattern is real but only used in one place in WireMate, so it's a judgment
call.

### App.vue layout shell → `BaseAppLayout`

Tiny but recurring: skip-link → sidebar → `<main inert>` wrapper. Could ship
as a `BaseAppLayout` component that takes a `:sidebar` slot and a `default`
slot for the page. Optional; the file is short enough that copy-pasting it
into the next backoffice is also fine.

---

## Tier 3 — leave behind

These read like they might be reusable but on inspection are too coupled to
WireMate / WireMock to be worth generalizing.

- **`CreateMock.vue`**, all of **`components/mock-form/*`** — every section is
  about stub mappings (request matching, response, scenarios, transformers,
  fault simulation, etc.).
- **`MoveModal.vue`** — built on the same pattern as `SelectProjectModal` but
  hardcoded to "move mock to project". Once `BaseEntityPickerModal` exists,
  this collapses into a four-line wrapper; not worth extracting separately.
- **`RequestDetailModal.vue`** — request-journal-specific.
- **`ImportPostmanModal.vue`** — Postman-collection-specific.
- **`WireMateLogo.vue`**, **`LandingPage.vue`** — brand-specific.
- **All `views/*.vue`** — domain views.
- **All `services/*`** — backend-coupled (WireMate + WireMock admin).
- **`composables/useMockValidation.ts`** — mock-shape-specific.
- **`utils/postmanImport.ts` / `postmanExport.ts`** — Postman-specific.
- **`utils/sanitizeNotificationHtml.ts`** — notification-payload-specific.
- **`utils/cookies.ts`** — generic but trivial; not worth a public API.

---

## Suggested rollout order

If you decide to do this in batches, this is the order that minimizes churn
on the consumer side:

1. **Composables first**: `useTheme`, `useThemeClasses`, `useEscapeKey`,
   `useDebouncedRef`, `useMobileSidebar`, `useToast`. They are leaves in the
   dependency graph — nothing in the lib depends on them yet, and the modal /
   sidebar extractions all need them.
2. **`httpColors`** utility — also a leaf.
3. **`BaseModalShell`** — used by `ConfirmModal`, `CloneModal/BaseTextInputModal`.
4. **`BaseCollapsibleSection`**, **`BaseConfirmModal`**, **`BaseTextInputModal`** —
   all depend on (1)–(3).
5. **`BaseNotFoundPage`** — independent, cheap.
6. **`BaseSidebar`** — biggest API surface; do this last when the design of
   slots/props can absorb lessons from steps 1–5.
7. (Optional) **`BaseEntityPickerModal`** and **`BaseAppLayout`** if a second
   backoffice consumer materializes.

---

## What WireMate ends up keeping

After everything in Tier 1 + Tier 2 moves, WireMate keeps: every view, every
service, every mock-form section, the WireMate logo, the landing page, the
notification HTML sanitizer, the Postman import/export, the cookie helpers,
`useMockValidation`, the request-detail modal, the Postman import modal — i.e.
exactly the things that *are* WireMate-the-product. The repo would drop from
~15 components to ~7, and `composables/` from 7 files to 1 (just
`useMockValidation`).
