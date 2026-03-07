<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Presupuestos</h1>
      <div class="filtros">
        <select v-model="filtroEstado" class="select-filtro">
          <option value="">Todos</option>
          <option value="borrador">Borrador</option>
          <option value="enviado">Enviado</option>
          <option value="aceptado">Aceptado</option>
          <option value="rechazado">Rechazado</option>
        </select>
      </div>
    </div>

    <div class="card">
      <div v-if="cargando" class="empty-state">Cargando...</div>

      <div v-else-if="!lista.length" class="empty-state">
        <p>No hay presupuestos todavía.</p>
        <p style="margin-top:8px; font-size:13px">
          Envía un audio de voz al bot de Telegram para crear el primero.
        </p>
      </div>

      <table v-else>
        <thead>
          <tr>
            <th>Número</th>
            <th>Cliente</th>
            <th>Fecha</th>
            <th>Total</th>
            <th>Estado</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in lista" :key="p.id">
            <td class="mono">{{ p.numero }}</td>
            <td>{{ p.clienteNombre || '—' }}</td>
            <td>{{ formatFecha(p.createdAt) }}</td>
            <td class="mono">{{ formatDinero(p.total) }}</td>
            <td><span class="badge" :class="`badge-${p.estado}`">{{ p.estado }}</span></td>
            <td>
              <RouterLink :to="`/presupuestos/${p.id}`" class="btn btn-ghost btn-sm">
                Ver
              </RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { presupuestosApi } from '@/api'
import { formatFecha, formatDinero } from '@/utils/formato'

const lista        = ref([])
const cargando     = ref(false)
const filtroEstado = ref('')

async function cargar() {
  cargando.value = true
  try {
    lista.value = await presupuestosApi.listar(filtroEstado.value)
  } finally {
    cargando.value = false
  }
}

watch(filtroEstado, cargar)
onMounted(cargar)
</script>

<style scoped>
.filtros { display: flex; gap: 8px; }
.select-filtro {
  padding: 7px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
  background: #fff;
}
.btn-sm { padding: 4px 10px; font-size: 12px; }
.mono { font-family: monospace; }
</style>