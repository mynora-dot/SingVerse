import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import './app/styles.css'
import App from './App.vue'
import { router } from './app/router/index'

window.addEventListener('singverse:session-expired', () => {
  if (router.currentRoute.value.path !== '/login') {
    void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

createApp(App).use(router).mount('#app')
