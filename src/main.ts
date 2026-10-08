import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import './styles/main.css'
import { useWorkspaceStore } from './stores/workspace'

const pinia = createPinia()
const app = createApp(App).use(pinia).use(router)
app.config.errorHandler = (error) => {
  useWorkspaceStore(pinia).addToast('操作未完成', error instanceof Error ? error.message : String(error), 'warning')
}
app.mount('#app')
