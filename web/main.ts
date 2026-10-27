import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { installUI } from '@devline/ui-web/controls'
import Settings from './PythonSettings.vue'
import { sdk } from './sdk'
import en from './en.json'
import ru from './ru.json'
import '@devline/ui-web/controls.css'
import './settings.scss'

void sdk.ready.then(() => {
  const app = createApp(Settings)
  installUI(app)
  app.use(createI18n({ legacy: false, locale: sdk.getContext().locale.startsWith('ru') ? 'ru' : 'en', fallbackLocale: 'en', messages: { en, ru } }))
  app.mount('#app')
})
