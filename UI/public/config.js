// =============================================================
//  Runtime configuration for WireMate UI.
//
//  Vite copies everything under /public verbatim into the
//  production image at /usr/share/nginx/html/, so this file is
//  served at `/config.js`. `index.html` loads it BEFORE the app
//  bundle, so `window.__APP_CONFIG__` is available the moment
//  Vue boots — letting operators change endpoints without a
//  rebuild.
//
//  Override at runtime by mounting a custom file over the one
//  baked into the image:
//
//    services:
//      wiremate-ui:
//        image: wiremate-ui
//        volumes:
//          - ./config.js:/usr/share/nginx/html/config.js:ro
//
//  (Replace `./config.js` with whatever path your compose file
//   uses — relative paths in compose are resolved against the
//   directory containing docker-compose.yml.)
// =============================================================
window.__APP_CONFIG__ = {
  // WireMock admin endpoint — the form posts /__admin/mappings,
  // /__admin/scenarios, etc. directly to this URL.
  WIREMOCK_ADMIN_URL: 'http://localhost:8080/__admin',

  // WireMate Spring Boot backend host — scheme + authority only, no
  // path. The app appends `/api` (and the resource path) at call
  // sites, so this should NOT include `/api`.
  API_BASE_URL: 'http://localhost:8081',
}
