import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import App from './App.vue'
import router from './router'
import i18n from './i18n'
import logger from './utils/logger'

// antd-vue base reset first, then our design tokens / global base so ours win.
import 'ant-design-vue/dist/reset.css'
import './global.css'

const app = createApp(App)

app.config.errorHandler = (err, _instance, info) => {
  logger.error('[app error]', info, err)
}

app.use(createPinia())
app.use(i18n)
app.use(router)
app.use(Antd)

app.mount('#app')
