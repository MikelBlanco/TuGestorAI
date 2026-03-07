<template>
  <div>
    <div class="page-header">
      <div>
        <RouterLink to="/presupuestos" class="back-link">← Presupuestos</RouterLink>
        <h1 class="page-title" style="margin-top:4px">{{ presupuesto?.numero }}</h1>
      </div>
      <div v-if="presupuesto" class="acciones">
        <button
          v-if="presupuesto.estado === 'borrador'"
          class="btn btn-primary"
          @click="cambiarEstado('enviado')"
        >
          Marcar como enviado
        </button>
        <button
          v-if="presupuesto.estado === 'enviado'"
          class="btn btn-success"
          @click="cambiarEstado('aceptado')"
        >
          Marcar como aceptado
        </button>
        <button
          v-if="presupuesto.estado === 'aceptado' && !facturaCreada"
          class="btn btn-primary"
          @click="crearFactura"
          :disabled="creandoFactura"
        >
          {{ creandoFactura ? 'Generando factura...' : 'Convertir en factura' }}
        </button>
      </div>
    </div>

    <div v-if="cargando" class="empty-state">Cargando...</div>

    <template v-else-if="presupuesto">
      <!-- Metadatos -->
      <div class="grid-2" style="margin-bottom:20px">
        <div class="card">
          <div class="detail-label">Cliente</div>
          <div class="detail-value">{{ presupuesto.clienteNombre || '—' }}</div>
          <div class="detail-label" style="margin-top:12px">Descripción</div>
          <div class="detail-value">{{ presupuesto.descripcion || '—' }}</div>
        </div>
        <div class="card">
          <div class="detail-label">Fecha</div>
          <div class="detail-value">{{ formatFecha(presupuesto.createdAt) }}</div>
          <div class="detail-label" style="margin-top:12px">Estado</div>
          <div>
            <span class="badge" :class="`badge-${presupuesto.estado}`">
              {{ presupuesto.estado }}
            </span>
          </div>
        </div>
      </div>

      <!-- Líneas -->
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
            <tr v-for="l in presupuesto.lineas" :key="l.id">
              <td>{{ l.concepto }}</td>
              <td>{{ traducirTipo(l.tipo) }}</td>
              <td style="text-align:center">{{ l.cantidad }}</td>
              <td style="text-align:right" class="mono">{{ formatDinero(l.precioUnitario) }}</td>
              <td style="text-align:right" class="mono">{{ formatDinero(l.importe) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Totales -->
      <div class="totales-card card">
        <div class="total-row">
          <span>Subtotal</span>
          <span class="mono">{{ formatDinero(presupuesto.subtotal) }}</span>
        </div>
        <div class="total-row">
          <span>IVA ({{ presupuesto.ivaPorcentaje }}%)</span>
          <span class="mono">{{ formatDinero(presupuesto.ivaImporte) }}</span>
        </div>
        <div class="total-row total-final">
          <span>TOTAL</span>
          <span class="mono">{{ formatDinero(presupuesto.total) }}</span>
        </div>
      </div>

      <p v-if="mensajeOk" class="msg-ok">{{ mensajeOk }}</p>
      <p v-if="mensajeError" class="msg-error">{{ mensajeError }}</p>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, RouterLink, useRouter } from 'vue-router'
import { presupuestosApi, facturasApi } from '@/api'
import { formatFecha, formatDinero } from '@/utils/formato'

const route   = useRoute()
const router  = useRouter()

const presupuesto   = ref(null)
const cargando      = ref(false)
const creandoFactura = ref(false)
const facturaCreada  = ref(false)
const mensajeOk      = ref('')
const mensajeError   = ref('')

async function cargar() {
  cargando.value = true
  try {
    presupuesto.value = await presupuestosApi.obtener(route.params.id)
  } finally {
    cargando.value = false
  }
}

async function cambiarEstado(estado) {
  try {
    await presupuestosApi.cambiarEstado(route.params.id, estado)
    presupuesto.value.estado = estado
  } catch (e) {
    mensajeError.value = e.message
  }
}

async function crearFactura() {
  creandoFactura.value = true
  mensajeError.value   = ''
  try {
    const factura = await facturasApi.crearDesdePresupuesto(Number(route.params.id))
    facturaCreada.value = true
    mensajeOk.value = `Factura ${factura.numero} creada correctamente.`
    setTimeout(() => router.push(`/facturas/${factura.id}`), 1500)
  } catch (e) {
    mensajeError.value = e.message
  } finally {
    creandoFactura.value = false
  }
}

function traducirTipo(tipo) {
  return tipo === 'material' ? 'Material' : 'Servicio'
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
.total-row { display: flex; justify-content: space-between; padding: 6px 0;
             border-bottom: 1px solid #f0f0f0; }
.total-row:last-child { border-bottom: none; }
.total-final { font-weight: 700; font-size: 16px; margin-top: 4px; color: #2980b9; }
.mono { font-family: monospace; }
.msg-ok    { margin-top: 16px; color: #27ae60; font-weight: 600; }
.msg-error { margin-top: 16px; color: #e74c3c; }
</style>