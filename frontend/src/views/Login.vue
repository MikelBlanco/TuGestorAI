<template>
  <div class="login-wrapper">
    <div class="login-card">
      <h1 class="login-title">TuGestorAI</h1>
      <p class="login-sub">Panel de gestión para autónomos</p>

      <form @submit.prevent="entrar">
        <label class="field-label">Tu ID de Telegram</label>
        <input
          v-model="telegramId"
          type="number"
          class="field-input"
          placeholder="Ej: 123456789"
          required
          autofocus
        />
        <p class="field-hint">
          Puedes ver tu ID enviando <code>/start</code> al bot en Telegram.
        </p>

        <p v-if="error" class="error-msg">{{ error }}</p>

        <button type="submit" class="btn btn-primary btn-full" :disabled="cargando">
          {{ cargando ? 'Comprobando...' : 'Entrar' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUsuarioStore } from '@/stores/usuario'

const router = useRouter()
const store  = useUsuarioStore()

const telegramId = ref('')
const error      = ref('')
const cargando   = ref(false)

async function entrar() {
  error.value   = ''
  cargando.value = true
  try {
    store.setTelegramId(telegramId.value)
    // Verificamos que el ID existe llamando al health check con la cabecera
    const res = await fetch('/api/presupuestos', {
      headers: { 'X-Telegram-Id': telegramId.value }
    })
    if (res.status === 401) {
      error.value = 'ID de Telegram no registrado. Usa /start en el bot primero.'
      store.logout()
    } else {
      // Guardamos datos básicos del usuario en el store
      store.setUsuario({ telegramId: telegramId.value, plan: 'free' })
      router.push('/presupuestos')
    }
  } catch {
    error.value = 'No se pudo conectar con el servidor.'
    store.logout()
  } finally {
    cargando.value = false
  }
}
</script>

<style scoped>
.login-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f6fa;
}
.login-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,.1);
  padding: 40px;
  width: 100%;
  max-width: 380px;
}
.login-title { font-size: 26px; font-weight: 700; color: #3498db; margin-bottom: 6px; }
.login-sub   { color: #7f8c8d; margin-bottom: 28px; }
.field-label { display: block; font-weight: 600; margin-bottom: 6px; }
.field-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  margin-bottom: 6px;
}
.field-input:focus { outline: none; border-color: #3498db; }
.field-hint  { font-size: 12px; color: #95a5a6; margin-bottom: 20px; }
.field-hint code { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; }
.error-msg   { color: #e74c3c; font-size: 13px; margin-bottom: 12px; }
.btn-full    { width: 100%; justify-content: center; padding: 11px; font-size: 14px; }
</style>