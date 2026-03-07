import { createRouter, createWebHistory } from 'vue-router'
import { useUsuarioStore } from '@/stores/usuario'

const routes = [
  {
    path: '/',
    redirect: '/presupuestos'
  },
  {
    path: '/login',
    component: () => import('@/views/Login.vue'),
    meta: { publica: true }
  },
  {
    path: '/presupuestos',
    component: () => import('@/views/Presupuestos.vue'),
    meta: { requiereAuth: true }
  },
  {
    path: '/presupuestos/:id',
    component: () => import('@/views/PresupuestoDetalle.vue'),
    meta: { requiereAuth: true }
  },
  {
    path: '/facturas',
    component: () => import('@/views/Facturas.vue'),
    meta: { requiereAuth: true }
  },
  {
    path: '/facturas/:id',
    component: () => import('@/views/FacturaDetalle.vue'),
    meta: { requiereAuth: true }
  },
  {
    path: '/perfil',
    component: () => import('@/views/Perfil.vue'),
    meta: { requiereAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const store = useUsuarioStore()
  if (to.meta.requiereAuth && !store.usuario) {
    return '/login'
  }
})

export default router