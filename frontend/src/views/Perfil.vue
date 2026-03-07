<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Mi perfil</h1>
      <button v-if="!editando" class="btn btn-ghost" @click="editando = true">Editar</button>
      <div v-else class="acciones">
        <button class="btn btn-primary" @click="guardar" :disabled="guardando">
          {{ guardando ? 'Guardando...' : 'Guardar' }}
        </button>
        <button class="btn btn-ghost" @click="cancelar">Cancelar</button>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <h3 class="section-title">Datos fiscales</h3>

        <div class="field">
          <label>Nombre / Razón social</label>
          <input v-if="editando" v-model="form.nombre" class="field-input" />
          <div v-else class="field-value">{{ datos.nombre || '—' }}</div>
        </div>
        <div class="field">
          <label>Nombre comercial</label>
          <input v-if="editando" v-model="form.nombreComercial" class="field-input" />
          <div v-else class="field-value">{{ datos.nombreComercial || '—' }}</div>
        </div>
        <div class="field">
          <label>NIF / CIF</label>
          <input v-if="editando" v-model="form.nif" class="field-input" placeholder="12345678A" />
          <div v-else class="field-value">{{ datos.nif || '—' }}</div>
        </div>
        <div class="field">
          <label>Dirección fiscal</label>
          <input v-if="editando" v-model="form.direccion" class="field-input" />
          <div v-else class="field-value">{{ datos.direccion || '—' }}</div>
        </div>
      </div>

      <div class="card">
        <h3 class="section-title">Contacto</h3>

        <div class="field">
          <label>Teléfono</label>
          <input v-if="editando" v-model="form.telefono" class="field-input" />
          <div v-else class="field-value">{{ datos.telefono || '—' }}</div>
        </div>
        <div class="field">
          <label>Email</label>
          <input v-if="editando" v-model="form.email" type="email" class="field-input" />
          <div v-else class="field-value">{{ datos.email || '—' }}</div>
        </div>

        <h3 class="section-title" style="margin-top:24px">Plan</h3>
        <span class="plan-badge" :class="datos.plan">
          Plan {{ datos.plan?.toUpperCase() }}
        </span>
        <p v-if="datos.plan === 'free'" class="plan-info">
          {{ datos.presupuestosMes || 0 }} / 5 presupuestos usados este mes.
        </p>
      </div>
    </div>

    <p v-if="mensajeOk"    class="msg-ok">{{ mensajeOk }}</p>
    <p v-if="mensajeError" class="msg-error">{{ mensajeError }}</p>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useUsuarioStore } from '@/stores/usuario'

const store = useUsuarioStore()

const datos    = ref({})
const editando = ref(false)
const guardando = ref(false)
const mensajeOk    = ref('')
const mensajeError = ref('')

const form = reactive({
  nombre: '', nombreComercial: '', nif: '', direccion: '', telefono: '', email: ''
})

function rellenarForm() {
  Object.assign(form, {
    nombre:         datos.value.nombre         || '',
    nombreComercial: datos.value.nombreComercial || '',
    nif:            datos.value.nif            || '',
    direccion:      datos.value.direccion      || '',
    telefono:       datos.value.telefono       || '',
    email:          datos.value.email          || ''
  })
}

function cancelar() {
  rellenarForm()
  editando.value = false
  mensajeError.value = ''
}

async function guardar() {
  // TODO: conectar con endpoint PUT /api/perfil cuando esté implementado
  guardando.value = true
  try {
    await new Promise(r => setTimeout(r, 400)) // simulación
    Object.assign(datos.value, form)
    editando.value = false
    mensajeOk.value = 'Perfil actualizado correctamente.'
    setTimeout(() => mensajeOk.value = '', 3000)
  } catch (e) {
    mensajeError.value = e.message
  } finally {
    guardando.value = false
  }
}

onMounted(async () => {
  // Por ahora cargamos desde el store; cuando haya endpoint GET /api/perfil lo usamos
  datos.value = store.usuario || {}
  rellenarForm()
})
</script>

<style scoped>
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.section-title { font-size: 13px; font-weight: 700; color: #7f8c8d; text-transform: uppercase; margin-bottom: 16px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: 12px; font-weight: 600; color: #7f8c8d; margin-bottom: 4px; }
.field-input {
  width: 100%; padding: 8px 10px; border: 1px solid #ddd;
  border-radius: 6px; font-size: 14px;
}
.field-input:focus { outline: none; border-color: #3498db; }
.field-value { font-size: 14px; padding: 4px 0; }
.acciones { display: flex; gap: 8px; }
.plan-badge {
  display: inline-block; padding: 4px 12px; border-radius: 12px;
  font-size: 12px; font-weight: 700; background: #95a5a6; color: #fff;
}
.plan-badge.pro { background: #f39c12; }
.plan-badge.free { background: #7f8c8d; }
.plan-info { margin-top: 8px; font-size: 13px; color: #7f8c8d; }
.msg-ok    { margin-top: 20px; color: #27ae60; font-weight: 600; }
.msg-error { margin-top: 20px; color: #e74c3c; }
</style>