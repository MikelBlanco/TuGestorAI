import { useUsuarioStore } from '@/stores/usuario'

const BASE = '/api'

/**
 * Wrapper sobre fetch que añade la cabecera de autenticación provisional
 * y lanza un error si el servidor devuelve un status de error.
 */
async function request(method, path, body = null) {
  const store = useUsuarioStore()

  const headers = { 'Content-Type': 'application/json' }
  if (store.telegramId) {
    headers['X-Telegram-Id'] = store.telegramId
  }

  const opts = { method, headers }
  if (body) opts.body = JSON.stringify(body)

  const res = await fetch(BASE + path, opts)

  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(err.error || `HTTP ${res.status}`)
  }

  // 204 No Content
  if (res.status === 204) return null
  return res.json()
}

// -------------------------------------------------------------------------
// Presupuestos
// -------------------------------------------------------------------------

export const presupuestosApi = {
  listar: (estado) =>
    request('GET', `/presupuestos${estado ? `?estado=${estado}` : ''}`),

  obtener: (id) =>
    request('GET', `/presupuestos/${id}`),

  cambiarEstado: (id, estado) =>
    request('PUT', `/presupuestos/${id}/estado`, { estado })
}

// -------------------------------------------------------------------------
// Facturas
// -------------------------------------------------------------------------

export const facturasApi = {
  listar: (estado) =>
    request('GET', `/facturas${estado ? `?estado=${estado}` : ''}`),

  obtener: (id) =>
    request('GET', `/facturas/${id}`),

  crearDesdePresupuesto: (presupuestoId, irpfPorcentaje = 15) =>
    request('POST', '/facturas', { presupuesto_id: presupuestoId, irpf_porcentaje: irpfPorcentaje }),

  cambiarEstado: (id, estado) =>
    request('PUT', `/facturas/${id}/estado`, { estado })
}