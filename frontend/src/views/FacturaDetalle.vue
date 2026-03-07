<template>
  <div>
    <div class="page-header">
      <div>
        <RouterLink to="/facturas" class="back-link">← Facturas</RouterLink>
        <h1 class="page-title" style="margin-top:4px">{{ factura?.numero }}</h1>
      </div>
      <div v-if="factura" class="acciones">
        <button
          v-if="factura.estado === 'borrador'"
          class="btn btn-primary"
          @click="cambiarEstado('emitida')"
        >
          Marcar como emitida
        </button>
        <button
          v-if="factura.estado === 'emitida'"
          class="btn btn-success"
          @click="cambiarEstado('pagada')"
        >
          Marcar como pagada
        </button>
      </div>
    </div>

    <div v-if="cargando" class="empty-state">Cargando...</div>

    <template v-else-if="factura">
      <div class="grid-2" style="margin-bottom:20px">
        <div class="card">
          <div class="detail-label">Cliente</div>
          <div class="detail-value">{{ factura.clienteNombre || '—' }}</div>
          <div class="detail-label" style="margin-top:12px">Descripción</div>
          <div class="detail-value">{{ factura.descripcion || '—' }}</div>
        </div>
        <div class="card">
          <div class="detail-label">Fecha emisión</div>
          <div class="detail-value">{{ formatFecha(factura.createdAt) }}</div>
          <div class="detail-label" style="margin-top:12px">Estado</div>
          <div>
            <span class="badge" :class="`badge-${factura.estado}`">{{ factura.estado }}</span>
          </div>
        </div>
      </div>

      <div class="card" style="margin-bottom:20px">
        <table>
          <thead>
            <tr>
              <th>Concepto</th>
              <th>Tipo</th>
              <th style="text-align:center">Cant.</th>
              <th style="text-align:right">Precio unit.</th>
              <th style="text-align:right">Importe</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in factura.lineas" :key="l.id">
              <td>{{ l.concepto }}</td>
              <td>{{ l.tipo === 'material' ? 'Material' : 'Servicio' }}</td>
              <td style="text-align:center">{{ l.cantidad }}</td>
              <td style="text-align:right" class="mono">{{ formatDinero(l.precioUnitario) }}</td>
              <td style="text-align:right" class="mono">{{ formatDinero(l.importe) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="totales-card card">
        <div class="total-row">
          <span>Base imponible</span>
          <span class="mono">{{ formatDinero(factura.subtotal) }}</span>
        </div>
        <div class="total-row">
          <span>IVA ({{ factura.ivaPorcentaje }}%)</span>
          <span class="mono">{{ formatDinero(factura.ivaImporte) }}</span>
        </div>
        <div class="total-row irpf-row">
          <span>Retención IRPF ({{ factura.irpfPorcentaje }}%)</span>
          <span class="mono">- {{ formatDinero(factura.irpfImporte) }}</span>
        </div>
        <div class="total-row total-final">
          <span>TOTAL</span>
          <span class="mono">{{ formatDinero(factura.total) }}</span>
        </div>
      </div>

      <p v-if="mensajeError" class="msg-error">{{ mensajeError }}</p>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { facturasApi } from '@/api'
import { formatFecha, formatDinero } from '@/utils/formato'

const route   = useRoute()
const factura = ref(null)
const cargando = ref(false)
const mensajeError = ref('')

async function cargar() {
  cargando.value = true
  try {
    factura.value = await facturasApi.obtener(route.params.id)
  } finally {
    cargando.value = false
  }
}

async function cambiarEstado(estado) {
  try {
    await facturasApi.cambiarEstado(route.params.id, estado)
    factura.value.estado = estado
  } catch (e) {
    mensajeError.value = e.message
  }
}

onMounted(cargar)
</script>

<style scoped>
.back-link { font-size: 13px; color: #3498db; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.detail-label { font-size: 11px; font-weight: 600; color: #95a5a6; text-transform: uppercase; margin-bottom: 4px; }
.detail-value { font-size: 15px; }
.acciones { display: flex; gap: 8px; }
.totales-card { max-width: 320px; margin-left: auto; }
.total-row { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid #f0f0f0; }
.total-row:last-child { border-bottom: none; }
.irpf-row { color: #e74c3c; }
.total-final { font-weight: 700; font-size: 16px; margin-top: 4px; color: #2980b9; }
.mono { font-family: monospace; }
.msg-error { margin-top: 16px; color: #e74c3c; }
</style>