/**
 * Formatea una fecha ISO a dd/MM/yyyy.
 */
export function formatFecha(isoString) {
  if (!isoString) return '—'
  const d = new Date(isoString)
  return d.toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/**
 * Formatea un número como importe en euros con separador de miles y decimales españoles.
 */
export function formatDinero(valor) {
  if (valor == null) return '0,00 €'
  return Number(valor).toLocaleString('es-ES', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }) + ' €'
}