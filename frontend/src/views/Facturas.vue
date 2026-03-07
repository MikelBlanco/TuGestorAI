<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Facturas</h1>
      <select v-model="filtroEstado" class="select-filtro">
        <option value="">Todas</option>
        <option value="borrador">Borrador</option>
        <option value="emitida">Emitida</option>
        <option value="pagada">Pagada</option>
        <option value="anulada">Anulada</option>
      </select>
    </div>

    <div class="card">
      <div v-if="cargando" class="empty-state">Cargando...</div>

      <div v-else-if="!lista.length" class="empty-state">
        <p>No hay facturas todavía.</p>
        <p style="margin-top:8px; font-size:13px">
          Convierte un presupuesto aceptado en factura desde su detalle.
        </p>
      </div>

      <table v-else>
        <thead>
          <tr>
            <th>Número</th>
            <th>Cliente</th>
            <th>Fecha</th>
            <th>Base imp.</th>
            <th>IRPF</th>
            <th>Total</th>
            <th>Estado</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in lista" :key="f.id">
            <td class="mono">{{ f.numero }}</td>
            <td>{{ f.clienteNombre || '—' }}</td>
            <td>{{ formatFecha(f.createdAt) }}</td>
            <td class="mono">{{ formatDinero(f.subtotal) }}</td>
            <td class="mono">-{{ formatDinero(f.irpfImporte) }}</td>
            <td class="mono"><strong>{{ formatDinero(f.total) }}</strong></td>
            <td><span class="badge" :class="`badge-${f.estado}`">{{ f.estado }}</span></td>
            <td>
              <RouterLink :to="`/facturas/${f.id}`" class="btn btn-ghost btn-sm">Ver</RouterLink>
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
import { facturasApi } from '@/api'
import { formatFecha, formatDinero } from '@/utils/formato'

const lista        = ref([])
const cargando     = ref(false)
const filtroEstado = ref('')

async function cargar() {
  cargando.value = true
  try {
    lista.value = await facturasApi.listar(filtroEstado.value)
  } finally {
    cargando.value = false
  }
}

watch(filtroEstado, cargar)
onMounted(cargar)
</script>

<style scoped>
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