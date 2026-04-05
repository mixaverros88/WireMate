import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// Suppress a single known false-positive prop warning from mgv-backoffice.
// BaseToast's compiled prop declaration says `mode: { type: Object }`, but the
// component's internal switch compares against a string enum (BaseToastEnum).
// Passing the string is actually required for the component to render
// correctly, so Vue's warning here is wrong. Filter only this specific case
// so real prop-validation warnings continue to surface.
if (import.meta.env.DEV) {
  app.config.warnHandler = (msg, _instance, trace) => {
    if (
      msg.includes('Invalid prop: type check failed for prop "mode"') &&
      trace.includes('BaseToast')
    ) {
      return
    }
    // eslint-disable-next-line no-console
    console.warn(`[Vue warn]: ${msg}${trace}`)
  }
}

app.use(router)
app.mount('#app')
