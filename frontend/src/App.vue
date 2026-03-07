<template>
  <div id="app">
    <nav class="sidebar" v-if="autenticado">
      <div class="sidebar-header">
        <span class="logo">TuGestorAI</span>
      </div>
      <ul class="nav-menu">
        <li><RouterLink to="/presupuestos">Presupuestos</RouterLink></li>
        <li><RouterLink to="/facturas">Facturas</RouterLink></li>
        <li><RouterLink to="/perfil">Mi perfil</RouterLink></li>
      </ul>
      <div class="sidebar-footer">
        <span class="plan-badge" :class="store.usuario?.plan">
          Plan {{ store.usuario?.plan?.toUpperCase() }}
        </span>
      </div>
    </nav>

    <main :class="{ 'con-sidebar': autenticado }">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useUsuarioStore } from '@/stores/usuario'

const store = useUsuarioStore()
const autenticado = computed(() => store.usuario !== null)
</script>

<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  font-size: 14px;
  color: #2c3e50;
  background: #f5f6fa;
}

#app { display: flex; min-height: 100vh; }

/* Sidebar */
.sidebar {
  width: 220px;
  background: #2c3e50;
  color: #ecf0f1;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.sidebar-header {
  padding: 24px 20px 16px;
  border-bottom: 1px solid #3d5166;
}
.logo { font-size: 18px; font-weight: 700; color: #3498db; }
.nav-menu { list-style: none; padding: 12px 0; flex: 1; }
.nav-menu li a {
  display: block;
  padding: 10px 20px;
  color: #bdc3c7;
  text-decoration: none;
  transition: background 0.15s;
}
.nav-menu li a:hover,
.nav-menu li a.router-link-active {
  background: #3d5166;
  color: #fff;
}
.sidebar-footer { padding: 16px 20px; border-top: 1px solid #3d5166; }
.plan-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 10px;
  background: #7f8c8d;
  color: #fff;
  text-transform: uppercase;
}
.plan-badge.pro { background: #f39c12; }

/* Contenido principal */
main {
  flex: 1;
  padding: 32px;
  overflow-y: auto;
}
main.con-sidebar { max-width: calc(100vw - 220px); }

/* Utilidades globales */
.card {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,.08);
  padding: 24px;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
  text-decoration: none;
}
.btn:hover { opacity: 0.85; }
.btn-primary  { background: #3498db; color: #fff; }
.btn-success  { background: #27ae60; color: #fff; }
.btn-danger   { background: #e74c3c; color: #fff; }
.btn-ghost    { background: transparent; color: #3498db; border: 1px solid #3498db; }

.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge-borrador  { background: #ecf0f1; color: #7f8c8d; }
.badge-enviado   { background: #d6eaf8; color: #2980b9; }
.badge-aceptado  { background: #d5f5e3; color: #27ae60; }
.badge-rechazado { background: #fadbd8; color: #e74c3c; }
.badge-emitida   { background: #d6eaf8; color: #2980b9; }
.badge-pagada    { background: #d5f5e3; color: #27ae60; }
.badge-anulada   { background: #fadbd8; color: #e74c3c; }

table { width: 100%; border-collapse: collapse; }
th, td { padding: 10px 12px; text-align: left; }
th { font-weight: 600; color: #7f8c8d; font-size: 12px; text-transform: uppercase;
     border-bottom: 2px solid #ecf0f1; }
td { border-bottom: 1px solid #f0f0f0; }
tr:last-child td { border-bottom: none; }
tr:hover td { background: #fafafa; }

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}
.page-title { font-size: 22px; font-weight: 700; }
.empty-state {
  text-align: center;
  padding: 48px;
  color: #95a5a6;
}
</style>