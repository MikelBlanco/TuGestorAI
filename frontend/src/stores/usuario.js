import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Store del usuario autenticado.
 * El telegramId se guarda en localStorage para persistir la sesión entre recargas.
 */
export const useUsuarioStore = defineStore('usuario', () => {
  const usuario = ref(null)
  const telegramId = ref(localStorage.getItem('telegramId') || null)

  function setTelegramId(id) {
    telegramId.value = String(id)
    localStorage.setItem('telegramId', telegramId.value)
  }

  function setUsuario(datos) {
    usuario.value = datos
  }

  function logout() {
    usuario.value = null
    telegramId.value = null
    localStorage.removeItem('telegramId')
  }

  return { usuario, telegramId, setTelegramId, setUsuario, logout }
})